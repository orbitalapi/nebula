package com.orbitalhq.nebula.kafka

import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.containerInfoFrom
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.events.ComponentLifecycleEventSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG
import org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG
import org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG
import org.apache.kafka.clients.producer.ProducerRecord
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Flux
import java.util.*

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

    override fun start(nebulaConfig: NebulaConfig): ComponentInfo<KafkaContainerConfig> {
        kafkaContainer = KafkaContainer(DockerImageName.parse(config.imageName))
            .withNetwork(nebulaConfig.network)
            .withNetworkAliases(config.componentName)
        eventSource.startContainerAndEmitEvents(kafkaContainer)

        val bootstrapServers = kafkaContainer.bootstrapServers
        logger.info { "Kafka container started - bootstrap servers: $bootstrapServers" }

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
                kafkaContainer.bootstrapServers
            ),
            type = type,
            name = name,
            id = id

        )
        // re-emit the running event now we're fully configured
        eventSource.running()
        return componentInfo!!
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
}

data class KafkaContainerConfig(val bootstrapServers: String)
