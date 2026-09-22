package com.orbitalhq.nebula

import com.orbitalhq.nebula.resources.StackResources

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

    /**
     * The files shipped alongside this stack's script.
     *
     * Exposed on the DSL (rather than handed to each component) so that any
     * provider which reads a file - `s3 { file(...) }`, and API Gateway's
     * `restApiFromFile(...)` - can resolve a path through the submitting
     * project's bundle rather than the Nebula server's own filesystem.
     *
     * [StackResources.NONE] when the stack was submitted without a bundle.
     */
    val resources: StackResources

    fun <T : InfrastructureComponent<*>> add(component: T): T
}
