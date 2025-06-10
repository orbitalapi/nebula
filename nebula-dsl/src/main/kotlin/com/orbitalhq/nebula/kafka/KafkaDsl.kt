package com.orbitalhq.nebula.kafka

import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.orbitalhq.nebula.InfraDsl
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.io.avro.convertMapToAvroGenericRecord
import com.orbitalhq.nebula.io.avro.serializeToBinaryAvro
import com.squareup.wire.schema.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DecoderFactory
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringSerializer
import kotlin.time.Duration


interface KafkaDsl : InfraDsl {
    fun kafka(imageName: String = "confluentinc/cp-kafka:6.2.2", componentName:ComponentName = "kafka", dsl: KafkaBuilder.() -> Unit): KafkaExecutor {
        val builder = KafkaBuilder(imageName, componentName)
        builder.dsl()
        return this.add(KafkaExecutor(builder.build()))
    }
}

enum class MessageSerializer(val serializerClass: Class<out Serializer<*>>) {
    String(StringSerializer::class.java),
    ByteArray(ByteArraySerializer::class.java),
}

// Builder for Kafka configuration
class KafkaBuilder(private val imageName: String, private val componentName: ComponentName) {
    private val producers = mutableListOf<ProducerConfig>()

    fun producer(frequency: Duration, topic: String,
                 keySerializer: MessageSerializer = MessageSerializer.String,
                 valueSerializer: MessageSerializer = MessageSerializer.String,
                 init: ProducerBuilder.() -> Unit) {
        producers.add(ProducerBuilder(frequency, topic, keySerializer.serializerClass, valueSerializer.serializerClass).apply(init).build())
    }

    fun build(): KafkaConfig = KafkaConfig(imageName, producers, componentName)
}

// Builder for individual producer configurations
class ProducerBuilder(
    private val frequency: Duration,
    private val topic: String,
    private val keySerializer: Class<out Serializer<*>>,
    private val valueSerializer: Class<out Serializer<*>>
) {
    private var messageGenerator: () -> Any = { EmptyMessage }
    val mapper = jacksonObjectMapper()
    fun message(generator: () -> Any) {
        messageGenerator = generator
    }

    /**
     * Converts a String that is encoded with Avro specific JSON format into
     * an Avro binary payload.
     *
     * Expects that the string is encoded using Avro's custom JSON format,
     * (eg., Union types for nullables, etc).
     */
    fun avroJsonMessage(schema: org.apache.avro.Schema, generator: () -> Any) {
        messageGenerator = {
            val payload = generator()

            if (payload != EmptyMessage) {
                require(payload is String) { "avroJsonMessage expects a JSON string as an input" }
                val decoder = DecoderFactory.get().jsonDecoder(schema, payload)
                val reader: DatumReader<GenericData.Record?> = GenericDatumReader(schema)
                val record = reader.read(null, decoder)!!
                serializeToBinaryAvro(record, schema)
            } else {
                EmptyMessage
            }
        }


    }
    /**
     * Converts a standard JSON string or Map into an Avro binary message.
     * For Avro-Json (which uses special nesting for union / nullable types), use
     * avroJsonMessage()
     */
    fun avroMessage(schema: org.apache.avro.Schema, generator: () -> Any) {
        messageGenerator = {
            val payload = generator()

            if (payload != EmptyMessage) {
                val record = when (payload) {
                    is String -> {
                        val map = mapper.readValue<Map<String,Any>>(payload)
                        convertMapToAvroGenericRecord(map, schema)
                    }
                    else -> {
                        val payloadAsMap = mapper.convertValue<Map<String,Any>>(payload)
                        convertMapToAvroGenericRecord(payloadAsMap, schema)
                    }
                }
                serializeToBinaryAvro(record, schema)
            } else {
                EmptyMessage
            }
        }
    }

    fun protoMessage(schema: Schema, typeName: String, generator: () -> Any) {
        val adapter = schema.protoAdapter(typeName, true)
        messageGenerator = {
            val payload = generator()
            if (payload != EmptyMessage) {
                val payloadAsMap = mapper.convertValue<Map<String,Any>>(payload)
                adapter.encode(payloadAsMap)
            } else {
                EmptyMessage
            }
        }
    }
    fun protoMessages(schema: Schema, typeName: String, generator: () -> List<Any>) {
        val adapter = schema.protoAdapter(typeName, true)
        messageGenerator = {
            val messages = generator()
            messages.map {
                val payloadAsMap = mapper.convertValue<Map<String,Any>>(it)
                adapter.encode(payloadAsMap)
            }
        }
    }

    fun jsonMessages(generator: () -> List<Any>) {
        messageGenerator = {
            val payload = generator()
            payload.map {
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(it)
            }
        }
    }
    fun jsonMessage(generator: () -> Any) {
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
    val keySerializer: Class<out Serializer<*>>,
    val valueSerializer: Class<out Serializer<*>>,
    val messageGenerator: () -> Any
)

// Placeholder for empty messages
object EmptyMessage