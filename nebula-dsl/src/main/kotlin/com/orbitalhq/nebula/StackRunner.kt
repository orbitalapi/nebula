package com.orbitalhq.nebula

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

class StackRunner() {
    private val logger = KotlinLogging.logger {}
    val stacks = ConcurrentHashMap<String, NebulaStack>()

    fun submit(stack: NebulaStack, name: String = stack.name) {
        this.stacks.compute(name) { key, existingSpec ->
            if (existingSpec != null) {
                logger.info { "Replacing spec $key" }
                shutDown(name)
            }
            stack
        }
        start(name)
    }

    val stackNames: List<String> = stacks.keys.toList()

    private fun start(name: String) {
        val spec = this.stacks[name] ?: error("Spec $name not found")
        logger.info { "Starting ${spec.name}" }
        spec.components.forEach { component ->
            component.start()
        }
    }

    inline fun <reified T : InfrastructureComponent> component(): List<T> {
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
        spec.components.forEach { it.stop() }
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