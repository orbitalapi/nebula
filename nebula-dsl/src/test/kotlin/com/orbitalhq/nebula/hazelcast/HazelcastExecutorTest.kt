package com.orbitalhq.nebula.hazelcast

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.config.NetworkConfig
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class HazelcastExecutorTest : DescribeSpec({
    lateinit var infra: StackRunner

    describe("hazelcast executor") {
        it("should create a hazelcast instance that can be connected to") {
            infra = stack {
                hazelcast {  }
            }.start()

            val config = ClientConfig().apply {
                networkConfig.apply {
                    addresses.add("localhost:${infra.hazelcast.single().componentInfo!!.componentConfig.port}")
                }
            }
            val client = HazelcastClient.newHazelcastClient(config)
            client.cluster.members.size.shouldBe(1)
        }
    }

}){
}