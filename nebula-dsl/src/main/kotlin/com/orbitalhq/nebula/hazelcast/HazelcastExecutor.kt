package com.orbitalhq.nebula.hazelcast

import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.containerInfoFrom
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.core.ComponentType
import com.orbitalhq.nebula.endpointFor
import com.orbitalhq.nebula.events.ComponentLifecycleEventSource
import com.orbitalhq.nebula.logging.LogStream
import com.orbitalhq.nebula.logging.LoggerName
import io.github.oshai.kotlinlogging.KotlinLogging
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Flux

val StackRunner.hazelcast: List<HazelcastExecutor>
    get() {
        return this.component<HazelcastExecutor>()
    }


data class HazelcastContainerConfig(
    val host: String,
    val port: Int
)

// The port Hazelcast listens on inside the container.
private const val HAZELCAST_INTERNAL_PORT = 5701
class HazelcastExecutor(private val config: HazelcastConfig, loggers: List<LoggerName>) : InfrastructureComponent<HazelcastContainerConfig> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private lateinit var container: GenericContainer<*>
    override val name: ComponentName = config.componentName
    override val type: ComponentType = "hazelcast"
    override val logStream: LogStream = LogStream(name, slf4jLoggerNames = loggers + listOf(HazelcastExecutor::class))
    private val eventSource = ComponentLifecycleEventSource(logStream = logStream)

    override fun start(nebulaConfig: NebulaConfig, hostConfig: HostConfig): ComponentInfo<HazelcastContainerConfig> {
        eventSource.starting()
        container = GenericContainer(DockerImageName.parse(config.imageName))
            .withExposedPorts(HAZELCAST_INTERNAL_PORT)
            .withNetwork(nebulaConfig.network)
            .withNetworkAliases(config.componentName)
        container.waitingFor(Wait.forListeningPort())
        eventSource.startContainerAndEmitEvents(container, name)

        val endpoint = nebulaConfig.endpointFor(container, config.componentName, HAZELCAST_INTERNAL_PORT)
        componentInfo = ComponentInfo(
            containerInfoFrom(container, endpoint.host),
            HazelcastContainerConfig(
                endpoint.host,
                endpoint.port
            ),
            type = type,
            name = name,
            id = id
        )
        eventSource.running()
        logger.info { "Hazelcast container started" }
        return componentInfo!!
    }

    override fun stop() {
        eventSource.stopping()
        eventSource.stopContainerAndEmitEvents(container)
    }

    override var componentInfo: ComponentInfo<HazelcastContainerConfig>? = null
        private set

    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = eventSource.events
    override val currentState: ComponentLifecycleEvent
        get() {
            return eventSource.currentState
        }
}