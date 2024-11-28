package com.orbitalhq.nebula.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.orbitalhq.nebula.InfraDsl
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.utils.NameGenerator
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringSerializer
import kotlin.reflect.KClass
import kotlin.time.Duration

interface KafkaDsl : InfraDsl {
    fun kafka(imageName: String = "confluentinc/cp-kafka:6.2.2", componentName:ComponentName = "kafka", dsl: KafkaBuilder.() -> Unit): KafkaExecutor {
        val builder = KafkaBuilder(imageName, componentName)
        builder.dsl()
        return this.add(KafkaExecutor(builder.build()))
    }
}


// Builder for Kafka configuration
class KafkaBuilder(private val imageName: String, private val componentName: ComponentName) {
    private val producers = mutableListOf<ProducerConfig>()

    fun producer(frequency: Duration, topic: String, init: ProducerBuilder.() -> Unit) {
        producers.add(ProducerBuilder(frequency, topic).apply(init).build())
    }

    fun build(): KafkaConfig = KafkaConfig(imageName, producers, componentName)
}

// Builder for individual producer configurations
class ProducerBuilder(
    private val frequency: Duration,
    private val topic: String,
    private val keySerializer: KClass<out Serializer<*>> = StringSerializer::class,
    private val valueSerializer: KClass<out Serializer<*>> = StringSerializer::class,
) {
    private var messageGenerator: () -> Any = { EmptyMessage }

    fun message(generator: () -> Any) {
        messageGenerator = generator
    }

    fun jsonMessages(generator: () -> List<Any>) {
        val mapper = jacksonObjectMapper()
        messageGenerator = {
            val payload = generator()
            payload.map {
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it)
            }
        }
    }
    fun jsonMessage(generator: () -> Any) {
        val mapper = jacksonObjectMapper()
        messageGenerator = {
            val payload = generator()
            if (payload != EmptyMessage) {
                val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
                json
            } else {
                EmptyMessage
            }
        }
    }

    fun build(): ProducerConfig {
        return ProducerConfig(frequency, topic, keySerializer, valueSerializer, messageGenerator)
    }
}

// Data classes to hold configurations
data class KafkaConfig(
    val imageName: String,
    val producers: List<ProducerConfig>,
    val componentName: ComponentName
)

data class ProducerConfig(
    val frequency: Duration, val topic: String,
    val keySerializer: KClass<out Serializer<*>>,
    val valueSerializer: KClass<out Serializer<*>>,
    val messageGenerator: () -> Any
)

// Placeholder for empty messages
object EmptyMessage