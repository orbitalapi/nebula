package com.orbitalhq.nebula

import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.StackStateEvent
import com.orbitalhq.nebula.events.StackStateEventSource
import com.orbitalhq.nebula.hazelcast.HazelcastDsl
import com.orbitalhq.nebula.http.HttpDsl
import com.orbitalhq.nebula.kafka.KafkaDsl
import com.orbitalhq.nebula.logging.LogMessage
import com.orbitalhq.nebula.logging.StackLogStream
import com.orbitalhq.nebula.mongo.MongoDsl
import com.orbitalhq.nebula.s3.S3Dsl
import com.orbitalhq.nebula.sql.SqlDsl
import com.orbitalhq.nebula.taxi.TaxiPublisherDsl
import com.orbitalhq.nebula.utils.NameGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean

typealias StackName = String

data class NebulaStackWithSource(
    val stack: NebulaStack,
    val source: String,
    val hostConfig: HostConfig
) {
    fun withName(id: String):NebulaStackWithSource {
        return this.copy(stack = stack.withName(id))
    }

    val name = stack.name
}

class NebulaStack(
    val name: StackName = NameGenerator.generateName(),
    initialComponents: List<InfrastructureComponent<*>> = emptyList()
) : InfraDsl, KafkaDsl, S3Dsl, HttpDsl, SqlDsl, HazelcastDsl, MongoDsl, TaxiPublisherDsl {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val _components = mutableListOf<InfrastructureComponent<*>>()

    private val isStarted = AtomicBoolean(false)

    val started: Boolean
        get() {
            return isStarted.get()
        }

    init {
        initialComponents.forEach { add(it) }
    }

    fun withName(name: StackName): NebulaStack {
        return NebulaStack(name, this._components)
    }

    private val logStream = StackLogStream()
    private val stackStateEventSource = StackStateEventSource()
    val lifecycleEvents:Flux<StackStateEvent> = stackStateEventSource.events
    val logMessages:Flux<LogMessage> = logStream.logMessages

    override fun <T : InfrastructureComponent<*>> add(component: T): T {
        if (isStarted.get()) {
            error("Cannot modify a stack after it has started")
        }
        _components.add(component)
        return component
    }

    fun startComponents(config: NebulaConfig, hostConfig: HostConfig): Map<String, ComponentInfo<out Any?>> {
        markStarted()
        stackStateEventSource.listenForEvents(name, components)
        logStream.attachLogStreams(components)
        return components.mapNotNull { component ->
            try {
                component.type to component.start(config, hostConfig)
            } catch (e: Exception) {
                // The component's own event source is responsible for emitting Failed
                // (which is what reaches clients); here we just stop the exception from
                // killing the stack's start thread, and let the remaining components start.
                logger.error(e) { "Component ${component.name} in stack $name failed to start" }
                null
            }
        }.toMap()
    }

    fun markStarted() {
        isStarted.set(true)
    }

    /**
     * Marks this stack as no longer running, so a later submission of the same
     * source is started rather than treated as already running.
     */
    fun markStopped() {
        isStarted.set(false)
    }

    override val components: List<InfrastructureComponent<*>>
        get() {
            return _components.toList()
        }
}

fun stack(init: NebulaStack.() -> Unit): NebulaStack {
    return NebulaStack().apply(init)
}