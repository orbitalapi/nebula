package com.orbitalhq.nebula

import org.testcontainers.containers.GenericContainer

interface InfrastructureComponent<T> {
    /**
     * A human readable type of component - this should almost always
     * be the same as whatever the root node is of the dsl used to build
     * this type of component (eg: http, kafka, etc).
     *
     * Used for display / diagnostics
     */
    val type: String
    fun start(): ComponentInfo<T>
    fun stop()
}

data class ComponentInfo<T>(
    val container: ContainerInfo?,
    val componentConfig: T
)

data class ContainerInfo(
    val containerId: String,
    val imageName: String,
    val containerName: String,
) {
    companion object {
        fun from(container: GenericContainer<*>):ContainerInfo {
            return ContainerInfo(
                containerId = container.containerId,
                imageName = container.dockerImageName,
                containerName = container.containerName,
            )
        }
    }

}
