package com.orbitalhq.nebula

import com.orbitalhq.nebula.http.HttpDsl
import com.orbitalhq.nebula.kafka.KafkaDsl
import com.orbitalhq.nebula.s3.S3Dsl
import com.orbitalhq.nebula.sql.SqlDsl

open class ServicesSpec() : InfraDsl, KafkaDsl, S3Dsl, HttpDsl, SqlDsl {

    private val _components = mutableListOf<InfrastructureComponent>()


    override fun <T : InfrastructureComponent> add(component: T): T {
        _components.add(component)
        return component
    }

    override val components: List<InfrastructureComponent>
        get() {
            return _components.toList()
        }
}

fun services(init: ServicesSpec.() -> Unit): ServicesSpec {
    return ServicesSpec().apply(init)
}