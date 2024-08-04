package com.orbitalhq.nebula

import com.orbitalhq.nebula.http.HttpDsl
import com.orbitalhq.nebula.kafka.KafkaDsl
import com.orbitalhq.nebula.s3.S3Dsl
import com.orbitalhq.nebula.sql.SqlDsl
import com.orbitalhq.nebula.utils.NameGenerator

class NebulaStack(val name: String = NameGenerator.generateName(), initialComponents: List<InfrastructureComponent> = emptyList()) : InfraDsl, KafkaDsl, S3Dsl, HttpDsl, SqlDsl {
    private val _components = mutableListOf<InfrastructureComponent>()

    init {
        initialComponents.forEach { add(it) }
    }

    override fun <T : InfrastructureComponent> add(component: T): T {
        _components.add(component)
        return component
    }

    override val components: List<InfrastructureComponent>
        get() {
            return _components.toList()
        }
}

fun stack(init: NebulaStack.() -> Unit): NebulaStack {
    return NebulaStack().apply(init)
}