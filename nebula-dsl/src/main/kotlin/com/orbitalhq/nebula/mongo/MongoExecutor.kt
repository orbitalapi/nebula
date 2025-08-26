package com.orbitalhq.nebula.mongo

import com.mongodb.client.MongoClients
import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.containerInfoFrom
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.core.ComponentType
import com.orbitalhq.nebula.core.HostNameAwareContainerConfig
import com.orbitalhq.nebula.events.ComponentLifecycleEventSource
import com.orbitalhq.nebula.utils.updateHostReferences
import io.github.oshai.kotlinlogging.KotlinLogging
import org.bson.Document
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Flux

val StackRunner.mongo: List<MongoExecutor>
    get() {
        return this.component<MongoExecutor>()
    }


class MongoExecutor(private val config: MongoConfig) : InfrastructureComponent<MongoContainerConfig> {
    override val name: ComponentName = config.componentName
    override val type: ComponentType = "mongo"
    private val eventSource = ComponentLifecycleEventSource()
    private lateinit var mongoContainer: MongoDBContainer

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun start(nebulaConfig: NebulaConfig): ComponentInfo<MongoContainerConfig> {
        mongoContainer = MongoDBContainer(DockerImageName.parse(config.imageName))
            .withNetwork(nebulaConfig.network)
            .withNetworkAliases(config.componentName)
        eventSource.startContainerAndEmitEvents(mongoContainer) {
            componentInfo = ComponentInfo(
                containerInfoFrom(mongoContainer),
                MongoContainerConfig(
                    mongoContainer.connectionString,
                    mongoContainer.firstMappedPort
                ),
                type = type,
                name = name,
                id = id
            )

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
    val port: Int
) : HostNameAwareContainerConfig<MongoContainerConfig> {
    override fun updateHostReferences(containerHost: String, publicHost: String): MongoContainerConfig {
        return MongoContainerConfig(
            connectionString.updateHostReferences(containerHost, publicHost),
            port
        )
    }

}