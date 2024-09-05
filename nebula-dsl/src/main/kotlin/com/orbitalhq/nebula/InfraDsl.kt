package com.orbitalhq.nebula

/**
 * Base interface for adding DSL support within the script.
 *
 * Provide an implementation that subtypes this interface,
 * with a function that you want to operate as your top-level
 * function name within the DSL.
 *
 * Then, add the interface to the list of implemented interfaces in InfraSpec
 */
interface InfraDsl {
    val components: List<InfrastructureComponent<*>>
    fun <T : InfrastructureComponent<*>> add(component: T): T
}