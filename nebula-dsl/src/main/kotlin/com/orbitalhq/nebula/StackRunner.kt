package com.orbitalhq.nebula

import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.StackStateEvent
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


    fun stackEvents(name:String):Flux<StackStateEvent> {
        val stack = this.stacks[name] ?: error("Stack $name not found")
        return stack.stack.lifecycleEvents
    }
    fun logs(name: String): Flux<LogMessage> {
        val stack = this.stacks[name] ?: error("Stack $name not found")
        return stack.stack.logMessages
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