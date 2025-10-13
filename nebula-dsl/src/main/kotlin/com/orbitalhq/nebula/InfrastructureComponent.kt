package com.orbitalhq.nebula

import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.core.ComponentType
import com.orbitalhq.nebula.core.ContainerInfo
import com.orbitalhq.nebula.logging.LogStream
import org.testcontainers.containers.GenericContainer
import reactor.core.publisher.Flux

interface InfrastructureComponent<T> {
    /**
     * A name assigned to the component.
     * The combination of name + type should be unique
     */
    val name: ComponentName
    /**
     * A human readable type of component - this should almost always
     * be the same as whatever the root node is of the dsl used to build
     * this type of component (eg: http, kafka, etc).
     *
     * Used for display / diagnostics
     */
    val type: ComponentType
    fun start(nebulaConfig: NebulaConfig, hostConfig: HostConfig = HostConfig.UNKNOWN): ComponentInfo<T>
    fun stop()

    val componentInfo: ComponentInfo<T>?
    val lifecycleEvents: Flux<ComponentLifecycleEvent>
    val currentState: ComponentLifecycleEvent

    val logStream: LogStream

    val id: String
        get() {
            return "$name-$type"
        }
}


fun containerInfoFrom(container: GenericContainer<*>):ContainerInfo {
    return ContainerInfo(
        containerId = container.containerId,
        imageName = container.dockerImageName,
        containerName = container.containerName,
        host = container.host
    )
}
