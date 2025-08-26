package com.orbitalhq.nebula.kafka

import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.containerInfoFrom
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.core.HostNameAwareContainerConfig
import com.orbitalhq.nebula.events.ComponentLifecycleEventSource
import com.orbitalhq.nebula.utils.updateHostReferences
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG
import org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG
import org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.errors.TopicExistsException
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Flux
import java.util.*
import java.util.concurrent.TimeUnit

val StackRunner.kafka: List<KafkaExecutor>
    get() {
        return this.component<KafkaExecutor>()
    }

class KafkaExecutor(private val config: KafkaConfig) : InfrastructureComponent<KafkaContainerConfig> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val type = "kafka"
    private lateinit var kafkaContainer: KafkaContainer
    private val producerJobs = mutableListOf<Job>()
    private val producers = mutableListOf<Producer<*, *>>()
    private val eventSource = ComponentLifecycleEventSource()

    override val name = config.componentName
    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = eventSource.events
    override val currentState: ComponentLifecycleEvent
        get() {
            return eventSource.currentState
        }

    override var componentInfo: ComponentInfo<KafkaContainerConfig>? = null
        private set

    override fun start(nebulaConfig: NebulaConfig, hostConfig: HostConfig): ComponentInfo<KafkaContainerConfig> {

        kafkaContainer = KafkaContainer(DockerImageName.parse(config.imageName))
            .let { container ->
                configureExternalListenerAddresses(hostConfig, container)
            }
            .withNetwork(nebulaConfig.network)
            .withNetworkAliases(config.componentName)

        eventSource.startContainerAndEmitEvents(kafkaContainer)

        val bootstrapServers = kafkaContainer.bootstrapServers
        logger.info { "Kafka container started - bootstrap servers: $bootstrapServers" }

        // Create topics with specified partitions
        createTopics(bootstrapServers, config.producers)

        config.producers.forEach { producerConfig ->
            val producer = createKafkaProducer(bootstrapServers, producerConfig)
            producers.add(producer)
            val job = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    fun writeToKafka(payload: Any) {
                        if (payload is EmptyMessage) {
                            // Do nothing
                        } else {
                            logger.info { "Emitting message to topic ${producerConfig.topic}" }
                            producer.send(ProducerRecord(producerConfig.topic, payload))
                        }
                    }

                    try {
                        val message = producerConfig.messageGenerator()
                        if (message is Iterable<*>) {
                            message.filterNotNull().forEach { writeToKafka(it) }
                        } else {
                            writeToKafka(message)
                        }

                    } catch (e: Exception) {
                        logger.error(e) { "Exception thrown producing Kafka message" }
                    }

                    delay(producerConfig.frequency.inWholeMilliseconds)
                }
            }
            producerJobs.add(job)
        }
        componentInfo = ComponentInfo(
            containerInfoFrom(kafkaContainer),
            KafkaContainerConfig(
                kafkaContainer.bootstrapServers,
                addressToProtocol
            ),
            type = type,
            name = name,
            id = id

        )
        // re-emit the running event now we're fully configured
        eventSource.running()
        return componentInfo!!
    }
    private val addressToProtocol = mutableMapOf<String,String>()

    /**
     * Configures listeners for the external addresses that
     * we're known by. Otherwise consumers who are connecting from
     * an external IP address can't connect to Kafka.
     *
     * Works by establishing a custom protocol mapping (test containers adds a TC-0 / TC-1 etc.. to the front)
     */
    private fun configureExternalListenerAddresses(
        hostConfig: HostConfig,
        container: KafkaContainer
    ): KafkaContainer {
        hostConfig.hostAddresses.forEachIndexed { index, externalAddress ->
            // each custom address gets a port incrementally higher than 9094
            val port = 9094 + index

            container.addExposedPort(port)
            container.withListener {
                try {
                    // If this doesn't throw an error, we're building the KAFKA_ADVERTISED_LISTENER part
                    val mappedPort = container.getMappedPort(port)
                    logger.info { "Configuring external Kafka listener for $externalAddress:$port" }
                    // Test containers maps these as TC-0://0.0.0.0:909X
                    val protocol = "TC-$index"
                    addressToProtocol.put(externalAddress, "$protocol://$externalAddress:$mappedPort")
                    "$externalAddress:$mappedPort"
                } catch (e: IllegalStateException) {
                    // The container isn't ready yet, so we're building the internal KAFKA_LISTENER
                    "0.0.0.0:$port"
                }
            }
        }
        return container
    }

    override fun stop() {
        eventSource.stopping()
        producerJobs.forEach { it.cancel() }
        producers.forEach { it.close() }
        eventSource.stopContainerAndEmitEvents(kafkaContainer)
    }

    val bootstrapServers: String
        get() {
            return kafkaContainer.bootstrapServers
        }

    private fun createKafkaProducer(
        bootstrapServers: String,
        producerConfig: ProducerConfig
    ): KafkaProducer<String, Any> {
        // Implementation to create and configure a KafkaProducer
        // This would include setting up serializers and other configurations
        val props = Properties().apply {
            put(BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
            put(KEY_SERIALIZER_CLASS_CONFIG, producerConfig.keySerializer.name)
            put(VALUE_SERIALIZER_CLASS_CONFIG, producerConfig.valueSerializer.name)
        }
        return KafkaProducer(props)
    }

    private fun createTopics(bootstrapServers: String, producers: List<ProducerConfig>) {
        val adminProps = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        }

        AdminClient.create(adminProps).use { adminClient ->
            // Group topics by name to handle duplicates and use max partitions
            val topicConfigs = producers.groupBy { it.topic }
                .mapValues { (_, configs) -> configs.maxOf { it.partitions } }
                .map { (topicName, partitions) ->
                    NewTopic(topicName, partitions, 1.toShort()) // replication factor of 1 for single broker
                }

            if (topicConfigs.isNotEmpty()) {
                try {
                    val result = adminClient.createTopics(topicConfigs)
                    result.all().get(30, TimeUnit.SECONDS) // Wait for completion with timeout
                    logger.info { "Created topics: ${topicConfigs.map { "${it.name()}(${it.numPartitions()} partitions)" }}" }
                } catch (e: TopicExistsException) {
                    logger.warn(e) { "Some topics already exist: ${e.message}" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create topics due to unexpected error: ${e.message}" }
                }
            }
        }
    }
}

data class KafkaContainerConfig(val bootstrapServers: String, private val externalAddressToListenerProtocol:Map<String,String>) : HostNameAwareContainerConfig<KafkaContainerConfig> {
    override fun updateHostReferences(containerHost: String, publicHost: String): KafkaContainerConfig {
        // If we configured a dedicated bootstrap server protocol for this host, then use that
        val dedicatedBootstrapServer = externalAddressToListenerProtocol[publicHost]
        return if (dedicatedBootstrapServer != null) {
            copy(bootstrapServers = dedicatedBootstrapServer)
        } else {
            this
        }
    }
}
