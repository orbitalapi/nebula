package com.orbitalhq.nebula

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap


class StackRunner() {
    private val logger = KotlinLogging.logger {}
    val stacks = ConcurrentHashMap<StackName, NebulaStack>()
    private val _stackState = ConcurrentHashMap<StackName, Map<String, ComponentInfo<*>>>()

    fun submit(stack: NebulaStack, name: StackName = stack.name) {
        this.stacks.compute(name) { key, existingSpec ->
            if (existingSpec != null) {
                logger.info { "Replacing spec $key" }
                shutDown(name)
            }
            stack
        }
        start(name)
    }

    val stateState: Map<StackName, Map<String, ComponentInfo<*>>>
        get() {
            return _stackState
        }


    private fun start(name: String) {
        val spec = this.stacks[name] ?: error("Spec $name not found")
        logger.info { "Starting ${spec.name}" }
        val state = spec.components.map { component ->
            component.type to component.start()
        }.toMap()
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
                it.stop()
            } catch (e:Exception) {
                logger.error(e) { "Error during shut down ${spec.name}" }
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