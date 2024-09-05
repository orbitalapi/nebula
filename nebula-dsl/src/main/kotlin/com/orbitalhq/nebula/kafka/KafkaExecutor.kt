package com.orbitalhq.nebula.kafka

import com.orbitalhq.nebula.ComponentInfo
import com.orbitalhq.nebula.ContainerInfo
import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.StackRunner
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
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.util.*

val StackRunner.kafka: List<KafkaExecutor>
    get() {
        return this.component<KafkaExecutor>()
    }

class KafkaExecutor(private val config: KafkaConfig) : InfrastructureComponent<KafkaContainerConfig> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val type: String = "kafka"
    private lateinit var kafkaContainer: KafkaContainer
    private val producerJobs = mutableListOf<Job>()
    private val producers = mutableListOf<Producer<*,*>>()

    override fun start(): ComponentInfo<KafkaContainerConfig> {
        kafkaContainer = KafkaContainer(DockerImageName.parse(config.imageName))
        kafkaContainer.start()

        val bootstrapServers = kafkaContainer.bootstrapServers
        logger.info { "Kafka container started - bootstrap servers: $bootstrapServers" }

        config.producers.forEach { producerConfig ->
            val producer = createKafkaProducer(bootstrapServers, producerConfig)
            producers.add(producer)
            val job = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {

                    try {
                        val message = producerConfig.messageGenerator()
                        logger.info { "Emitting message to topic ${producerConfig.topic}" }
                        producer.send(ProducerRecord(producerConfig.topic, message))
                    } catch (e: Exception) {
                        logger.error(e) { "Exception thrown producing Kafka message" }
                    }

                    delay(producerConfig.frequency.inWholeMilliseconds)
                }
            }
            producerJobs.add(job)
        }
        return ComponentInfo(
            ContainerInfo.from(kafkaContainer),
            KafkaContainerConfig(
                kafkaContainer.bootstrapServers
            )
        )
    }

    override fun stop() {
        producerJobs.forEach { it.cancel() }
        producers.forEach { it.close() }
        kafkaContainer.stop()
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
            put(KEY_SERIALIZER_CLASS_CONFIG, producerConfig.keySerializer.java.name)
            put(VALUE_SERIALIZER_CLASS_CONFIG, producerConfig.valueSerializer.java.name)
        }
        return KafkaProducer(props)
    }
}

data class KafkaContainerConfig(val bootstrapServers: String)
