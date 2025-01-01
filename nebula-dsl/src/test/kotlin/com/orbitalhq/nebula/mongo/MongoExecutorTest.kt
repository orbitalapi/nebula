package com.orbitalhq.nebula.mongo

import com.mongodb.client.MongoClients
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize

class MongoExecutorTest : DescribeSpec({
    describe("Mongo Executor") {
        it("should create a mongo db") {
            val infra = stack {
                mongo(databaseName = "testDb") {
                    collection(
                        "people", data = listOf(
                            mapOf(
                                "name" to "Jimmy",
                                "age" to 25
                            ),
                            mapOf(
                                "name" to "Jack",
                                "age" to 43
                            )
                        )
                    )
                }
            }.start()

            val mongoInfra = infra.mongo.single()
            val mongoClient = MongoClients.create(mongoInfra.componentInfo!!.componentConfig.connectionString)
            val db = mongoClient.getDatabase("testDb")
            val collection = db.getCollection("people")
            val allRecords = collection.find()
                .toList()
            allRecords.shouldHaveSize(2)

        }
    }
})