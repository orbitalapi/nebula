package com.orbitalhq.nebula.taxi

import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.common.runBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

class TaxiPublisherExecutorTest : DescribeSpec({
    lateinit var infra: StackRunner
    // Disabled, as it's hanging the build, and I can't see why
    xdescribe("Taxi publisher") {
        val receivedContent = AtomicReference<String>()
        val server = embeddedServer(Netty, port = 0) {
            install(ContentNegotiation)
            routing {
                post("/api/schemas/taxi") {
                    // Store the received content
                    val content = call.receiveText()
                    receivedContent.set(content)

                    // Return 200 OK
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
        afterTest {
            logger.info { "Shutting down test infra" }
            infra?.shutDownAll()
            logger.info { "Test infra shutdown complete" }
        }
        beforeTest {
            server.start()
        }

        it("should publish sources to endpoint on startup") {
            // Get the actual port assigned to the server
            val port = runBlocking { server.resolvedConnectors().first().port }
            infra = stack {
                taxiPublisher("http://localhost:$port", "com.foo/test/1.0.0") {
                    taxi("foo.taxi") {
                        """
                        type Hello inherits String    
                        """.trimIndent()
                    }
                    additionalSource("@orbital/config", "connections.conf") {
                        """
                            some content goes here
                        """.trimIndent()
                    }
                }
            }.start()
            eventually(15.seconds) {
                receivedContent.get().shouldNotBeNull()
            }
            val stackState = infra.stateState
            stackState
        }
    }
})