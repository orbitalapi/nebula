package com.orbitalhq.nebula.redis

import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI

class RedisExecutorTest : DescribeSpec({
    lateinit var infra: StackRunner

    describe("redis executor") {
        it("should create a redis instance that can be connected to") {
            infra = stack {
                redis { }
            }.start()

            val port = infra.redis.single().componentInfo!!.componentConfig.port
            val redisUri = RedisURI.Builder
                .redis("localhost", port)
                .build()

            val client = RedisClient.create(redisUri)
            val connection = client.connect()
            val syncCommands = connection.sync()

            // Test basic operations
            syncCommands.set("test-key", "test-value")
            val value = syncCommands.get("test-key")
            value shouldBe "test-value"

            connection.close()
            client.shutdown()
        }

        it("should support multiple operations") {
            infra = stack {
                redis { }
            }.start()

            val port = infra.redis.single().componentInfo!!.componentConfig.port
            val redisUri = RedisURI.Builder
                .redis("localhost", port)
                .build()

            val client = RedisClient.create(redisUri)
            val connection = client.connect()
            val syncCommands = connection.sync()

            // Test multiple key-value operations
            syncCommands.set("key1", "value1")
            syncCommands.set("key2", "value2")
            syncCommands.set("key3", "value3")

            syncCommands.get("key1") shouldBe "value1"
            syncCommands.get("key2") shouldBe "value2"
            syncCommands.get("key3") shouldBe "value3"

            // Test delete operation
            syncCommands.del("key2")
            syncCommands.get("key2") shouldBe null

            connection.close()
            client.shutdown()
        }
    }

}) {
}
