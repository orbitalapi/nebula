package com.orbitalhq.nebula

class InfrastructureExecutor(val spec: ServicesSpec) {
    fun execute() {
        spec.components.forEach { component ->
            component.start()
        }
    }

    inline fun <reified T:InfrastructureComponent> component(): T {
        return spec.components.filterIsInstance<T>()
            .first()
    }

    fun shutDown() {
        spec.components.forEach { it.stop() }
    }
}



fun ServicesSpec.start(): InfrastructureExecutor {
    val executor = InfrastructureExecutor(this)
    executor.execute()
    return executor
}