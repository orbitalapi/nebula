package com.orbitalhq.nebula

import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.StackStateEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.testcontainers.containers.Network
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class NebulaConfig(
    val networkName: String = "nebula_network",
    val network: Network = Network.newNetwork()
)
class StackRunner(private val config: NebulaConfig = NebulaConfig()) {
    private val logger = KotlinLogging.logger {}
    val stacks = ConcurrentHashMap<StackName, NebulaStack>()
    private val _stackState = ConcurrentHashMap<StackName, Map<String, ComponentInfo<*>>>()

    fun submit(stack: NebulaStack, name: StackName = stack.name, startAsync: Boolean = false): Flux<StackStateEvent> {
        this.stacks.compute(name) { key, existingSpec ->
            if (existingSpec != null) {
                logger.info { "Replacing spec $key" }
                shutDown(name)
            }
            stack
        }
        if (startAsync) {
            thread {
                start(name)
            }
        } else {
            start(name)
        }


        return stackEvents(name)
    }

    val stateState: Map<StackName, Map<String, ComponentInfo<*>>>
        get() {
            return _stackState
        }

    fun stackEvents(name:String):Flux<StackStateEvent> {
        val stack = this.stacks[name] ?: error("Stack $name not found")
        return stack.lifecycleEvents
    }


    private fun start(name: String) {
        val stack = this.stacks[name] ?: error("Stack $name not found")
        stack.lifecycleEvents.subscribe { event ->
            logger.info { event.toString() }
        }
        logger.info { "Starting ${stack.name}" }
        val state: Map<String, ComponentInfo<out Any?>> = stack.startComponents(config)

        _stackState[name] = state
    }

    inline fun <reified T : InfrastructureComponent<*>> component(): List<T> {
        return this.stacks.values.flatMap {
            it.components.filterIsInstance<T>()
        }
    }

    fun shutDownAll() {
        this.stacks.keys.forEach { shutDown(it) }
    }

    fun shutDown(name: String) {
        val spec = this.stacks[name] ?: error("Spec $name not found")
        logger.info { "Shutting down ${spec.name}" }
        spec.components.forEach {
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
fun NebulaStack.start(): StackRunner {
    val executor = StackRunner()
    executor.submit(this)
    return executor
}