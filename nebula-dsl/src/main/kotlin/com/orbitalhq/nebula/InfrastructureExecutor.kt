package com.orbitalhq.nebula

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

class InfrastructureExecutor() {
    private val logger = KotlinLogging.logger {}
    val specs = ConcurrentHashMap<String, NebulaStack>()

    fun execute(spec: NebulaStack, name: String = spec.name) {
        this.specs.compute(name) { key, existingSpec ->
            if (existingSpec != null) {
                logger.info { "Replacing spec $key" }
                shutDown(name)
            }
            spec
        }
        start(name)

    }

    private fun start(name: String) {
        val spec = this.specs[name] ?: error("Spec $name not found")
        logger.info { "Starting ${spec.name}" }
        spec.components.forEach { component ->
            component.start()
        }
    }

    inline fun <reified T : InfrastructureComponent> component(): List<T> {
        return this.specs.values.flatMap {
            it.components.filterIsInstance<T>()
        }
    }
    fun shutDownAll() {
        this.specs.keys.forEach { shutDown(it) }
    }

    fun shutDown(name: String) {
        val spec = this.specs[name] ?: error("Spec $name not found")
        logger.info { "Shutting down ${spec.name}" }
        spec.components.forEach { it.stop() }
    }
}

/**
 * Convenience for testing
 */
fun NebulaStack.start(): InfrastructureExecutor {
    val executor = InfrastructureExecutor()
    executor.execute(this)
    return executor
}