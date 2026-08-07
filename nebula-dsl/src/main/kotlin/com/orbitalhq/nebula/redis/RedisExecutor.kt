package com.orbitalhq.nebula.redis

import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.containerInfoFrom
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.core.ComponentType
import com.orbitalhq.nebula.events.ComponentLifecycleEventSource
import com.orbitalhq.nebula.logging.LogStream
import com.orbitalhq.nebula.logging.LoggerName
import io.github.oshai.kotlinlogging.KotlinLogging
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Flux

val StackRunner.redis: List<RedisExecutor>
    get() {
        return this.component<RedisExecutor>()
    }


data class RedisContainerConfig(
    val port: Int
)
class RedisExecutor(private val config: RedisConfig, loggers: List<LoggerName>) : InfrastructureComponent<RedisContainerConfig> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private lateinit var container: GenericContainer<*>
    override val name: ComponentName = config.componentName
    override val type: ComponentType = "redis"
    override val logStream: LogStream = LogStream(name, slf4jLoggerNames = loggers + listOf(RedisExecutor::class))
    private val eventSource = ComponentLifecycleEventSource(logStream = logStream)

    override fun start(nebulaConfig: NebulaConfig, hostConfig: HostConfig): ComponentInfo<RedisContainerConfig> {
        eventSource.starting()
        container = GenericContainer(DockerImageName.parse(config.imageName))
            .withExposedPorts(6379)
            .withNetwork(nebulaConfig.network)
            .withNetworkAliases(config.componentName)
        container.waitingFor(Wait.forListeningPort())
        eventSource.startContainerAndEmitEvents(container, name)

        componentInfo = ComponentInfo(
            containerInfoFrom(container),
            RedisContainerConfig(
                container.firstMappedPort
            ),
            type = type,
            name = name,
            id = id
        )
        eventSource.running()
        logger.info { "Redis container started" }
        return componentInfo!!
    }

    override fun stop() {
        eventSource.stopping()
        eventSource.stopContainerAndEmitEvents(container)
    }

    override var componentInfo: ComponentInfo<RedisContainerConfig>? = null
        private set

    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = eventSource.events
    override val currentState: ComponentLifecycleEvent
        get() {
            return eventSource.currentState
        }
}
