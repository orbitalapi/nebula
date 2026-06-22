package com.orbitalhq.nebula

import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.StackStateEvent
import com.orbitalhq.nebula.events.computeStackStateEventFor
import com.orbitalhq.nebula.logging.LogMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.testcontainers.containers.Network
import reactor.core.publisher.Flux
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class NebulaConfig(
    val networkName: String = "nebula_network",
    val network: Network = Network.newNetwork()
)

/**
 * Captures the details of the host, as captured when a stack
 * is submitted.
 * We can use this to determine things like the IP address that the
 * stack is accessible via, to configure components who are strict
 * about which network interfaces they listen on (eg., Kafka)
 */
data class HostConfig(
    val hostAddresses:List<String>
) {
    companion object {
        val UNKNOWN = HostConfig(emptyList())
    }
}
class StackRunner(private val config: NebulaConfig = NebulaConfig()) {
    private val logger = KotlinLogging.logger {}
    val stacks = ConcurrentHashMap<StackName, NebulaStackWithSource>()
    private val _stackState = ConcurrentHashMap<StackName, Map<String, ComponentInfo<*>>>()

    fun submit(submittedStack: NebulaStackWithSource, name: StackName = submittedStack.name, startAsync: Boolean = false): Flux<StackStateEvent> {
        val storedStack = this.stacks.compute(name) { key, existingSpec ->
            if (existingSpec != null) {
                if (existingSpec.source == submittedStack.source) {
                    logger.info { "Received duplicate submission for spec $key - reusing existing stack" }
                    return@compute existingSpec
                } else {
                    logger.info { "Replacing spec $key" }
                    shutDown(name)
                }
            }
            submittedStack
        } ?: error("After submitting stack $name, no stack was created.")

        // Only start if the stack that got stored was the one that got submitted.
        // Otherwise it's someone elses stack, and already running
        if (submittedStack == storedStack || !storedStack.stack.started) {
            if (startAsync) {
                thread {
                    start(name)
                }
            } else {
                start(name)
            }
        } else {
            logger.info { "Not starting stack ${storedStack.name} as stack is already running" }
        }



        return stackEvents(name)
    }

    val stateState: Map<StackName, Map<String, ComponentInfo<*>>>
        get() {
            return _stackState
        }

    fun getStackComponents(name: StackName) : Map<String, ComponentInfo<*>>? {
        return stateState[name]
    }

    /** The source script a stack was submitted with, or null if no such stack exists. */
    fun sourceFor(name: StackName): String? = this.stacks[name]?.source

    /** Whether a stack with this name has been submitted (regardless of running state). */
    fun isSubmitted(name: StackName): Boolean = this.stacks.containsKey(name)


    fun stackEvents(name:String):Flux<StackStateEvent> {
        val stack = this.stacks[name] ?: error("Stack $name not found")
        return stack.stack.lifecycleEvents
    }
    fun logs(name: String): Flux<LogMessage> {
        val stack = this.stacks[name] ?: error("Stack $name not found")
        return stack.stack.logMessages
    }

    /**
     * Returns a point-in-time snapshot of every known stack and the current
     * lifecycle state of its components.
     *
     * Unlike [stateState], this includes each component's current lifecycle state,
     * making it suitable for driving an admin UI that polls for status.
     */
    fun snapshot(): List<StackStateEvent> = this.stacks.keys.mapNotNull { snapshot(it) }

    /**
     * Returns a point-in-time snapshot of a single stack, or null if no stack
     * with the given name exists.
     */
    fun snapshot(name: StackName): StackStateEvent? {
        val stack = this.stacks[name] ?: return null
        val components = stack.stack.components
        val stateMap = components.associateWith { it.currentState.state }
        return computeStackStateEventFor(name, stateMap, components)
    }

    private fun findComponent(stackName: StackName, componentId: String): InfrastructureComponent<*> {
        val stack = this.stacks[stackName] ?: error("Stack $stackName not found")
        return stack.stack.components.find { it.id == componentId }
            ?: error("Component $componentId not found in stack $stackName")
    }

    /**
     * Stops a single component within a stack, leaving the rest of the stack running.
     */
    fun stopComponent(stackName: StackName, componentId: String) {
        val component = findComponent(stackName, componentId)
        logger.info { "Stopping component $componentId in stack $stackName" }
        component.stop()
    }

    /**
     * (Re)starts a single component within a stack that was previously stopped.
     */
    fun startComponent(stackName: StackName, componentId: String) {
        val stackWithSource = this.stacks[stackName] ?: error("Stack $stackName not found")
        val component = findComponent(stackName, componentId)
        logger.info { "Starting component $componentId in stack $stackName" }
        component.start(config, stackWithSource.hostConfig)
    }


    private fun start(name: String) {
        val stackWithSource = this.stacks[name] ?: error("Stack $name not found")
        val stack = stackWithSource.stack
        val hostConfig = stackWithSource.hostConfig
        stack.lifecycleEvents.subscribe { event ->
            logger.info { event.toString() }
        }
        logger.info { "Starting ${stack.name}" }
        val state: Map<String, ComponentInfo<out Any?>> = stack.startComponents(config, hostConfig)

        _stackState[name] = state
    }

    inline fun <reified T : InfrastructureComponent<*>> component(): List<T> {
        return this.stacks.values.flatMap {
            it.stack.components.filterIsInstance<T>()
        }
    }

    /**
     * (Re)starts every not-yet-running component in a previously-submitted stack.
     * Use this to bring a stopped stack back up without resubmitting its script.
     */
    fun startStack(name: StackName) {
        val stackWithSource = this.stacks[name] ?: error("Stack $name not found")
        logger.info { "Starting stack $name" }
        stackWithSource.stack.components.forEach { component ->
            if (component.currentState.state != ComponentState.Running) {
                try {
                    component.start(config, stackWithSource.hostConfig)
                } catch (e: Exception) {
                    logger.error(e) { "Error starting component ${component.name} in stack $name" }
                }
            }
        }
    }

    /**
     * Stops a stack (if running) and removes it entirely from the runner, so it
     * no longer appears in [stateState] / snapshots. Tolerant of unknown names.
     */
    fun removeStack(name: StackName) {
        if (this.stacks.containsKey(name)) {
            shutDown(name)
        }
        this.stacks.remove(name)
        this._stackState.remove(name)
    }

    fun shutDownAll() {
        this.stacks.keys.forEach { shutDown(it) }
    }

    fun shutDown(name: String) {
        val spec = this.stacks[name] ?: error("Spec $name not found")
        logger.info { "Shutting down ${spec.name}" }
        spec.stack.components.forEach {
            try {
                logger.info { "Stopping ${it.name}" }
                it.stop()
                logger.info { "Stopped ${it.name} successfully" }
            } catch (e:Exception) {
                logger.error(e) { "Error during shut down of component ${it.name} in spec ${spec.name}" }
            }
        }
    }

}

/**
 * Convenience for testing
 */
fun NebulaStackWithSource.start(): StackRunner {
    val executor = StackRunner()
    executor.submit(this)
    return executor
}

/**
 * Testing method.
 * Uses a random UUID in the source to replicate legacy behaviour, where submission
 * would not check for duplicates, or replace existing stacks if present
 */
fun NebulaStack.start(): StackRunner {
    val executor = StackRunner()
    val stackWithSource = NebulaStackWithSource(this, "Source not provided - Random UUID follows - ${UUID.randomUUID()}" , hostConfig = HostConfig.UNKNOWN)
    executor.submit(stackWithSource)
    return executor
}