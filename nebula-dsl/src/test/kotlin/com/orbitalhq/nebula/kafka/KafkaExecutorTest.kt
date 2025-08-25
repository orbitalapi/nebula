package com.orbitalhq.nebula.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.io.avro.avroSchema
import com.orbitalhq.nebula.io.avro.deserializeFromBinaryAvro
import com.orbitalhq.nebula.io.protobuf.protobufSchema
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import com.orbitalhq.nebula.utils.duration
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOptions
import reactor.kotlin.test.test
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.reflect.KClass

class KafkaExecutorTest : DescribeSpec({
    lateinit var infra: StackRunner
    describe("Kafka broker") {

        afterTest {
            infra?.shutDownAll()
        }

        it("should build a kafka broker that emits a message every 100ms") {
            infra = stack {
                kafka {
                    producer("100ms".duration(), "stockQuotes") {
                        jsonMessage {
                            mapOf(
                                "symbol" to listOf("GBP/USD", "AUD/USD", "NZD/USD").random(),
                                "price" to Random.nextDouble(0.8, 0.95).toBigDecimal()
                            )
                        }
                    }
                }
            }.start()

            val consumer = createKafkaConsumer(infra.kafka.single().bootstrapServers, topic = "stockQuotes")
            consumer.receive()
                .take(2)
                .test()
                .expectSubscription()
                .expectNextMatches { it.value().isJsonWithKeys("symbol", "price") }
                .expectNextMatches { it.value().isJsonWithKeys("symbol", "price") }
                .expectComplete()
                .verify()

        }

        it("can read and write protobuf") {
            val schema = protobufSchema(
                """
syntax = "proto3";

// Message representing a currency pair with its current price
message Quote {
  string symbol = 1;
  double price = 2;
}       
                """.trimIndent()
            )
            infra = stack {

                kafka {
                    producer("100ms".duration(), "stockQuotes", valueSerializer = MessageSerializer.ByteArray) {
                        protoMessage(schema, "Quote") {
                            mapOf(
                                "symbol" to listOf("GBP/USD", "AUD/USD", "NZD/USD").random(),
                                "price" to Random.nextDouble(0.8, 0.95).toBigDecimal()
                            )
                        }
                    }
                }
            }.start()
            val adapter = schema.protoAdapter("Quote", true)
            val consumer = createKafkaConsumer(
                infra.kafka.single().bootstrapServers,
                topic = "stockQuotes",
                keyDeserializer = StringDeserializer::class,
                valueDeserializer = ByteArrayDeserializer::class
            )
            consumer.receive()
                .take(2)
                .test()
                .expectSubscription()
                .expectNextMatches { reciever ->

                    val decoded = adapter.decode(reciever.value()) as Map<String, Any>
                    decoded.shouldContainKeys("symbol", "price")
                    true
                }
                .expectNextMatches { reciever ->
                    val decoded = adapter.decode(reciever.value()) as Map<String, Any>
                    decoded.shouldContainKeys("symbol", "price")
                    true
                }
                .expectComplete()
                .verify(Duration.ofSeconds(5))
        }

        describe("working with Avro") {
            val avroSchema = avroSchema(
                """
    {
      "type": "record",
      "name": "TestMessage",
      "namespace": "com.example.test",
      "doc": "A record to test various Avro features",
      "fields": [
        {"name": "id", "type": "string", "doc": "A unique identifier for the message"},
        {"name": "timestamp", "type": {"type": "long", "logicalType": "timestamp-millis"}, "doc": "Timestamp of the message"},
        {"name": "name", "type": ["null", "string"], "default": null, "doc": "Name of the entity, nullable"},
        {"name": "age", "type": ["null", "int"], "default": null, "doc": "Age of the entity, nullable"},
        {"name": "status", "type": {"type": "enum", "name": "Status", "symbols": ["ACTIVE", "INACTIVE", "DELETED"]}, "doc": "Status of the entity"},
        {"name": "tags", "type": {"type": "array", "items": "string"}, "doc": "A list of tags associated with the entity"},
        {"name": "metadata", "type": {"type": "map", "values": "string"}, "doc": "A map of metadata key-value pairs"},
        {
          "name": "address",
          "type": {"type": "record", "name": "Address", "fields": [
            {"name": "street", "type": "string", "doc": "Street address"},
            {"name": "city", "type": "string", "doc": "City"},
            {"name": "zip_code", "type": "string", "doc": "Postal code"}
          ]},
          "doc": "Nested record representing an address"
        },
        {"name": "is_active", "type": "boolean", "default": true, "doc": "Indicates if the entity is active"},
        {"name": "score", "type": ["null", "double"], "default": null, "doc": "Score of the entity, nullable"}
      ]
    }   
                """.trimIndent()
            )
            it("can read and write avro from standard json") {
                infra = stack {

                    kafka {
                        producer("100ms".duration(), "stockQuotes", valueSerializer = MessageSerializer.ByteArray) {
                            avroMessage(avroSchema) {
                                """{
  "id": "12345",
  "timestamp": 1654027200000,
  "name": "John Doe",
  "age": 30,
  "status": "ACTIVE",
  "tags": ["test", "avro"],
  "metadata": {
    "key1": "value1",
    "key2": "value2"
  },
  "address": {
    "street": "123 Main St",
    "city": "Anytown",
    "zip_code": "12345"
  },
  "is_active": true,
  "score": 99.5
}"""
                            }
                        }
                    }
                }.start()
                val consumer = createKafkaConsumer(
                    infra.kafka.single().bootstrapServers,
                    topic = "stockQuotes",
                    keyDeserializer = StringDeserializer::class,
                    valueDeserializer = ByteArrayDeserializer::class
                )
                consumer.receive()
                    .take(1)
                    .test()
                    .expectSubscription()
                    .expectNextMatches { reciever ->
                        val decoded = deserializeFromBinaryAvro(reciever.value(), avroSchema)
                        decoded.get("name").toString() shouldBe "John Doe"
                        true
                    }
                    .expectComplete()
                    .verify(Duration.ofSeconds(5))
            }

            it("can read and write avro from avro-specific json") {
                infra = stack {

                    kafka {
                        producer("100ms".duration(), "stockQuotes", valueSerializer = MessageSerializer.ByteArray) {
                            avroJsonMessage(avroSchema) {
                                """{
  "id": "12345",
  "timestamp": 1654027200000,
   "name": {"string": "John Doe"},
    "age": {"int": 30},
  "status": "ACTIVE",
  "tags": ["test", "avro"],
  "metadata": {
    "key1": "value1",
    "key2": "value2"
  },
  "address": {
    "street": "123 Main St",
    "city": "Anytown",
    "zip_code": "12345"
  },
  "is_active": true,
   "score": {"double": 99.5}
}"""
                            }
                        }
                    }
                }.start()
                val consumer = createKafkaConsumer(
                    infra.kafka.single().bootstrapServers,
                    topic = "stockQuotes",
                    keyDeserializer = StringDeserializer::class,
                    valueDeserializer = ByteArrayDeserializer::class
                )
                consumer.receive()
                    .take(1)
                    .test()
                    .expectSubscription()
                    .expectNextMatches { reciever ->
                        val decoded = deserializeFromBinaryAvro(reciever.value(), avroSchema)
                        decoded.get("name").toString() shouldBe "John Doe"
                        true
                    }
                    .expectComplete()
                    .verify(Duration.ofSeconds(5))
            }

        }


        xit("can share state between two streams") {
            infra = stack {
                kafka {
                    val counter = AtomicInteger(0)
                    val pendingOrders: ConcurrentLinkedQueue<Map<String, Any>> = ConcurrentLinkedQueue()
                    producer("200ms".duration(), "orders") {
//                        jsonMessage {
//                            println("Creating new order...")
//                            val order = mapOf(
//                                "orderId" to counter.incrementAndGet(),
//                                "symbol" to listOf("GBP/USD", "AUD/USD", "NZD/USD").random(),
//                                "quantity" to Random.nextInt(500, 2000) * 100,
//                                "status" to "Submitted"
//                            )
//                            pendingOrders.add(order)
//                            order
//                        }
                    }
                    producer("100ms".duration(), "trades") {
                        jsonMessages {
                            val items = mutableListOf<Map<String, Any>>()
                            while (true) {
                                val item = pendingOrders.poll() ?: break
                                items.add(item)
                            }
                            val trades = items.map { item ->
                                val rate = Random.nextDouble(0.8, 0.95).toBigDecimal()
                                val price = (item["quantity"] as Int).toBigDecimal().multiply(rate)
                                val trade = mapOf(
                                    "orderId" to item["orderId"],
                                    "rate" to rate,
                                    "price" to price,
                                    "status" to "Filled"
                                )
                                trade
                            }
                            trades
                        }

                    }
                }
            }.start()

            val orderConsumer = createKafkaConsumer(infra.kafka.single().bootstrapServers, topic = "orders")
            var receviedOrder: Map<String, Any> = emptyMap()
            orderConsumer.receive()
                .take(5)
                .test()
                .expectSubscription()
                .expectNextMatches {
                    it.value().isJsonWithKeys("orderId", "symbol", "quantity")
                    receviedOrder = jacksonObjectMapper().readValue<Map<String, Any>>(it.value())
                    true
                }
                .expectNextMatches {
                    it.value().isJsonWithKeys("orderId", "symbol", "quantity")
                    val nextOrder = jacksonObjectMapper().readValue<Map<String, Any>>(it.value())
                    nextOrder["orderId"].shouldNotBe(receviedOrder["orderId"])
                    true
                }
                .thenCancel()
                .verify(Duration.ofSeconds(5))

            val tradesConsumer = createKafkaConsumer(infra.kafka.single().bootstrapServers, topic = "trades")
            tradesConsumer.receive()
                .take(1)
                .test()
                .expectSubscription()
                .expectNextMatches {
                    it.value().isJsonWithKeys("orderId", "rate")
                    val receivedTrade = jacksonObjectMapper().readValue<Map<String, Any>>(it.value())
                    receivedTrade["orderId"].shouldBe(receviedOrder["orderId"])
                    true
                }
                .thenCancel()
                .verify(Duration.ofSeconds(5))
        }
    }
})

fun <K, V> createKafkaConsumer(
    bootstrapServers: String,
    topic: String,
    keyDeserializer: KClass<out Deserializer<K>>,
    valueDeserializer: KClass<out Deserializer<V>>
): KafkaReceiver<K, V> {
    val consumerProps = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
        ConsumerConfig.GROUP_ID_CONFIG to "test-consumer-group",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to keyDeserializer.java,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to valueDeserializer.java,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest"
    )

    val receiverOptions = ReceiverOptions.create<String, String>(consumerProps)
        .subscription(listOf(topic))

    return KafkaReceiver.create(receiverOptions) as KafkaReceiver<K, V>
}

fun createKafkaConsumer(bootstrapServers: String, topic: String): KafkaReceiver<String, String> {
    return createKafkaConsumer(bootstrapServers, topic, StringDeserializer::class, StringDeserializer::class)
}

fun String.isJsonWithKeys(vararg keys: String): Boolean {
    val map = jacksonObjectMapper().readValue<Any>(this)
    map.shouldBeInstanceOf<Map<String, Any>>()
    map.shouldContainKeys(*keys)
    return true
}