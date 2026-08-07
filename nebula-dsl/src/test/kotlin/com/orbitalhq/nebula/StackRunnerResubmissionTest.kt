package com.orbitalhq.nebula

import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.http.http
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import kotlinx.coroutines.runBlocking

class StackRunnerResubmissionTest : DescribeSpec({

    fun httpStack(response: String) = NebulaStackWithSource(
        stack {
            http {
                get("/hello") { call ->
                    call.respondText(response)
                }
            }
        },
        source = "script-$response",
        hostConfig = HostConfig.UNKNOWN
    ).withName("resubmission-test")

    describe("resubmitting a stack") {

        it("should leave an http component Running when the same source is submitted again") {
            val runner = StackRunner()
            try {
                runner.submit(httpStack("v1"))
                runner.http.single().currentState.state shouldBe ComponentState.Running

                // Re-submitting the same source (as Orbital does on reconnect) must not
                // restart the components - previously this rebound the http port, failed,
                // and left the component stuck in Starting.
                runner.submit(httpStack("v1"))

                val component = runner.http.single()
                component.currentState.state shouldBe ComponentState.Running

                runBlocking {
                    val client = HttpClient()
                    client.get("${component.baseUrl}/hello").let {
                        it.status shouldBe HttpStatusCode.OK
                        it.bodyAsText() shouldBe "v1"
                    }
                    client.close()
                }
            } finally {
                runner.shutDownAll()
            }
        }

        it("should restart an http component when a different source is submitted") {
            val runner = StackRunner()
            try {
                runner.submit(httpStack("v1"))
                val originalComponent = runner.http.single()

                runner.submit(httpStack("v2"))

                val component = runner.http.single()
                component shouldNotBe originalComponent
                component.currentState.state shouldBe ComponentState.Running

                runBlocking {
                    val client = HttpClient()
                    client.get("${component.baseUrl}/hello").let {
                        it.status shouldBe HttpStatusCode.OK
                        it.bodyAsText() shouldBe "v2"
                    }
                    client.close()
                }
            } finally {
                runner.shutDownAll()
            }
        }

        it("should restart a stack that was shut down when the same source is submitted again") {
            val runner = StackRunner()
            try {
                runner.submit(httpStack("v1"))
                runner.shutDown("resubmission-test")
                runner.http.single().currentState.state shouldBe ComponentState.Stopped

                runner.submit(httpStack("v1"))

                val component = runner.http.single()
                component.currentState.state shouldBe ComponentState.Running
            } finally {
                runner.shutDownAll()
            }
        }
    }
})
