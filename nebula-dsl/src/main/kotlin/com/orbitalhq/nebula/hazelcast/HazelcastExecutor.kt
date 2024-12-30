package com.orbitalhq.nebula.hazelcast

import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.containerInfoFrom
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.core.ComponentType
import com.orbitalhq.nebula.events.ComponentLifecycleEventSource
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
    val port: Int
)
class HazelcastExecutor(private val config: HazelcastConfig) : InfrastructureComponent<HazelcastContainerConfig> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private lateinit var container: GenericContainer<*>
    override val name: ComponentName = config.componentName
    override val type: ComponentType = "hazelcast"
    private val eventSource = ComponentLifecycleEventSource()

    override fun start(nebulaConfig: NebulaConfig): ComponentInfo<HazelcastContainerConfig> {
        eventSource.starting()
        container = GenericContainer(DockerImageName.parse(config.imageName))
            .withExposedPorts(5701)
            .withNetwork(nebulaConfig.network)
            .withNetworkAliases(config.componentName)
        container.waitingFor(Wait.forListeningPort())
        eventSource.startContainerAndEmitEvents(container)

        componentInfo = ComponentInfo(
            containerInfoFrom(container),
            HazelcastContainerConfig(
                container.firstMappedPort
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