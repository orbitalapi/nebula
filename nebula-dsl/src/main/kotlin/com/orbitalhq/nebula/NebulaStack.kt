package com.orbitalhq.nebula

import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.StackStateEvent
import com.orbitalhq.nebula.events.StackStateEventSource
import com.orbitalhq.nebula.hazelcast.HazelcastDsl
import com.orbitalhq.nebula.http.HttpDsl
import com.orbitalhq.nebula.kafka.KafkaDsl
import com.orbitalhq.nebula.mongo.MongoDsl
import com.orbitalhq.nebula.s3.S3Dsl
import com.orbitalhq.nebula.sql.SqlDsl
import com.orbitalhq.nebula.utils.NameGenerator
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean

typealias StackName = String

class NebulaStack(
    val name: StackName = NameGenerator.generateName(),
    initialComponents: List<InfrastructureComponent<*>> = emptyList()
) : InfraDsl, KafkaDsl, S3Dsl, HttpDsl, SqlDsl, HazelcastDsl, MongoDsl {
    private val _components = mutableListOf<InfrastructureComponent<*>>()

    private val isStarted = AtomicBoolean(false)

    init {
        initialComponents.forEach { add(it) }
    }

    fun withName(name: StackName): NebulaStack {
        return NebulaStack(name, this._components)
    }

    private val stackStateEventSource = StackStateEventSource()
    val lifecycleEvents:Flux<StackStateEvent> = stackStateEventSource.events

    override fun <T : InfrastructureComponent<*>> add(component: T): T {
        if (isStarted.get()) {
            error("Cannot modify a stack after it has started")
        }
        _components.add(component)
        return component
    }

    fun startComponents(config: NebulaConfig): Map<String, ComponentInfo<out Any?>> {
        stackStateEventSource.listenForEvents(name, components)
        return components.associate { component ->
            component.type to component.start(config)
        }
    }

    override val components: List<InfrastructureComponent<*>>
        get() {
            return _components.toList()
        }
}

fun stack(init: NebulaStack.() -> Unit): NebulaStack {
    return NebulaStack().apply(init)
}