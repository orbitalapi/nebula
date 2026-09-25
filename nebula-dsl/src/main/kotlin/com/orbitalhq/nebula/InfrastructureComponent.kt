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


fun containerInfoFrom(container: GenericContainer<*>, host: String = container.host):ContainerInfo {
    return ContainerInfo(
        containerId = container.containerId,
        imageName = container.dockerImageName,
        containerName = container.containerName,
        host = host
    )
}

/**
 * The host + port a consumer should use to reach a container, resolved
 * according to the configured [ConsumerConnectivity] mode.
 */
data class Endpoint(val host: String, val port: Int) {
    val hostAndPort: String get() = "$host:$port"
}

/**
 * Resolves the [Endpoint] a consumer should use for [container], given the
 * connectivity mode on this config.
 *
 * @param alias the container's network alias (conventionally its componentName)
 * @param internalPort the port the service listens on inside the container
 */
fun NebulaConfig.endpointFor(
    container: GenericContainer<*>,
    alias: String,
    internalPort: Int
): Endpoint = when (connectivity) {
    ConsumerConnectivity.HOST -> Endpoint(container.host, container.getMappedPort(internalPort))
    ConsumerConnectivity.NETWORK -> Endpoint(alias, internalPort)
}

/**
 * An address that reaches exactly this container from another container on the Nebula network.
 *
 * Prefer this over the network alias (conventionally the componentName) when a container
 * must reach one specific component: stacks share a network and can reuse component names,
 * so an alias like `s3` may resolve to another stack's container.
 *
 * This is the container's IP rather than its name, as docker's generated names contain
 * underscores, which aren't valid in a hostname (and AWS SDKs reject them in endpoint URLs).
 */
val GenericContainer<*>.uniqueNetworkHost: String
    get() {
        val network = this.network ?: error("Container $containerName is not attached to a Nebula network")
        return containerInfo.networkSettings.networks.values
            .firstOrNull { it.networkID == network.id }
            ?.ipAddress
            ?: error("Container $containerName has no address on network ${network.id}")
    }
