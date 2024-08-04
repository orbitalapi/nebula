package com.orbitalhq.nebula.http

import com.orbitalhq.nebula.InfrastructureExecutor
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.coroutines.runBlocking

class HttpExecutorTest : DescribeSpec({

    lateinit var infra: InfrastructureExecutor
    describe("Http Executor") {
        afterTest {
            infra?.shutDownAll()
        }

        it("should set up an HTTP API with various endpoints") {
            infra = stack {
                http {
                    get("/hello") { call ->
                        call.respondText("Hello, World!")
                    }
                    post("/echo") { call ->
                        val body = call.receiveText()
                        call.respondText(body)
                    }
                    get("/users/{id}") { call ->
                        val id = call.parameters["id"]
                        call.respondText("User $id")
                    }
                    put("/update/{id}") { call ->
                        val id = call.parameters["id"]
                        val body = call.receiveText()
                        call.respondText("Updated user $id with $body")
                    }
                    delete("/delete/{id}") { call ->
                        val id = call.parameters["id"]
                        call.respondText("Deleted user $id", status = HttpStatusCode.NoContent)
                    }
                }
            }.start()

            val baseUrl = infra.http.single().baseUrl
            val client = HttpClient()

            runBlocking {
                // Test GET
                client.get("$baseUrl/hello").let {
                    it.status.shouldBe(HttpStatusCode.OK)
                    it.bodyAsText().shouldBe("Hello, World!")
                }

                // Test POST
                client.post("$baseUrl/echo") {
                    setBody("Echo this!")
                }.let {
                    it.status.shouldBe(HttpStatusCode.OK)
                    it.bodyAsText().shouldBe("Echo this!")
                }

                // Test GET with path parameter
                client.get("$baseUrl/users/123").let {
                    it.status.shouldBe(HttpStatusCode.OK)
                    it.bodyAsText().shouldBe("User 123")
                }

                // Test PUT
                client.put("$baseUrl/update/456") {
                    setBody("New data")
                }.let {
                    it.status.shouldBe(HttpStatusCode.OK)
                    it.bodyAsText().shouldBe("Updated user 456 with New data")
                }

                // Test DELETE
                client.delete("$baseUrl/delete/789").let {
                    it.status.shouldBe(HttpStatusCode.NoContent)
                }
            }

            client.close()
        }
    }
})