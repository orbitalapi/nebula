package com.orbitalhq.nebula.mongo

import com.mongodb.client.MongoClients
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
import org.bson.Document
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Flux

val StackRunner.mongo: List<MongoExecutor>
    get() {
        return this.component<MongoExecutor>()
    }


class MongoExecutor(private val config: MongoConfig, loggers: List<LoggerName>) : InfrastructureComponent<MongoContainerConfig> {
    override val name: ComponentName = config.componentName
    override val type: ComponentType = "mongo"
    override val logStream: LogStream = LogStream(name, slf4jLoggerNames = loggers + listOf(MongoExecutor::class))
    private val eventSource = ComponentLifecycleEventSource(logStream = logStream)
    private lateinit var mongoContainer: MongoDBContainer


    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun start(nebulaConfig: NebulaConfig, hostConfig: HostConfig): ComponentInfo<MongoContainerConfig> {
        mongoContainer = MongoDBContainer(DockerImageName.parse(config.imageName))
            .withNetwork(nebulaConfig.network)
            .withNetworkAliases(config.componentName)
        eventSource.startContainerAndEmitEvents(mongoContainer, name) {
            val internalPort = MONGO_INTERNAL_PORT
            val endpoint = nebulaConfig.endpointFor(mongoContainer, config.componentName, internalPort)
            // The TestContainers connectionString embeds the host-mapped coordinates;
            // swap them for the resolved endpoint so the emitted string matches the mode.
            val emittedConnectionString = mongoContainer.connectionString.replace(
                "${mongoContainer.host}:${mongoContainer.getMappedPort(internalPort)}",
                endpoint.hostAndPort
            )
            componentInfo = ComponentInfo(
                containerInfoFrom(mongoContainer, endpoint.host),
                MongoContainerConfig(
                    emittedConnectionString,
                    endpoint.host,
                    endpoint.port
                ),
                type = type,
                name = name,
                id = id
            )

            // Nebula's own client connects via the host-mapped connection string.
            val mongoClient = MongoClients.create(mongoContainer.connectionString)
            // Force creation of the database
            val mongoDb = mongoClient.getDatabase(config.databaseName)
            config.collections.forEach { collection ->
                logger.info { "Creating Mongo collection ${collection.name}" }
                val mongoCollection = mongoDb.getCollection(collection.name)
                val documents = collection.data.map { data ->
                    val document = Document()
                    data.entries.forEach { entry -> document.append(entry.key, entry.value) }
                    document
                }
                if (documents.isNotEmpty()) {
                    mongoCollection.insertMany(documents)
                }

                logger.info { "Inserted ${documents.size} documents to collection ${collection.name}" }
            }

        }
        return componentInfo!!
    }

    override fun stop() {
        eventSource.stopContainerAndEmitEvents(mongoContainer)
    }

    override var componentInfo: ComponentInfo<MongoContainerConfig>? = null
        private set
    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = eventSource.events
    override val currentState: ComponentLifecycleEvent
        get() {
            return eventSource.currentState
        }
}


data class MongoContainerConfig(
    val connectionString: String,
    val host: String,
    val port: Int
)

// The port MongoDB listens on inside the container.
private const val MONGO_INTERNAL_PORT = 27017