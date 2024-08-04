package com.orbitalhq.nebula.kafka

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.start
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.utils.duration
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOptions
import reactor.kotlin.test.test
import java.util.*
import kotlin.random.Random

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
    }
})

fun createKafkaConsumer(bootstrapServers: String, topic: String): KafkaReceiver<String, String> {
    val consumerProps = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
        ConsumerConfig.GROUP_ID_CONFIG to "test-consumer-group",
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest"
    )

    val receiverOptions = ReceiverOptions.create<String, String>(consumerProps)
        .subscription(listOf(topic))

    return KafkaReceiver.create(receiverOptions)
}

fun String.isJsonWithKeys(vararg keys: String): Boolean {
    val map = jacksonObjectMapper().readValue<Any>(this)
    map.shouldBeInstanceOf<Map<String, Any>>()
    map.shouldContainKeys(*keys)
    return true
}