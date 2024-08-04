package com.orbitalhq.nebula.kafka

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
import org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG
import org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG
import org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG
import org.apache.kafka.clients.producer.ProducerRecord
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import java.util.*

val StackRunner.kafka: List<KafkaExecutor>
    get() {
        return this.component<KafkaExecutor>()
    }

class KafkaExecutor(private val config: KafkaConfig) : InfrastructureComponent {
    companion object {
        private val logger = KotlinLogging.logger {}
    }
    private lateinit var kafkaContainer: KafkaContainer
    private val producerJobs = mutableListOf<Job>()

    override fun start() {
        kafkaContainer = KafkaContainer(DockerImageName.parse(config.imageName))
        kafkaContainer.start()

        val bootstrapServers = kafkaContainer.bootstrapServers

        config.producers.forEach { producerConfig ->
            val producer = createKafkaProducer(bootstrapServers, producerConfig)
            val job = CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {

                    try {
                        val message = producerConfig.messageGenerator()
                        logger.info { "Emitting message to topic ${producerConfig.topic}" }
                        producer.send(ProducerRecord(producerConfig.topic, message))
                    } catch (e:Exception) {
                        logger.error(e) { "Exception thrown producing Kafka message"}
                    }

                    delay(producerConfig.frequency.inWholeMilliseconds)
                }
            }
            producerJobs.add(job)
        }
    }

    override fun stop() {
        producerJobs.forEach { it.cancel() }
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

