package com.orbitalhq.nebula.runtime.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.ResourceContent
import com.orbitalhq.nebula.core.StackBundle
import com.orbitalhq.nebula.core.StackStateEvent
import com.orbitalhq.nebula.http.http
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes

/**
 * The route Orbital actually submits over. Covers a bundle arriving on
 * `/stream/stacks`, being unpacked, and the running stack serving a file that
 * shipped with it - plus the script-only message still working on the same
 * socket, which is what keeps an older Orbital talking to this server.
 */
class StreamStacksBundleTest : DescribeSpec({

    describe("/stream/stacks bundle submissions") {

        it("unpacks a submitted bundle and serves a file that shipped with the script") {
            val stackRunner = StackRunner()
            val server = NebulaServer(port = 0, stackExecutor = stackRunner)
            val applicationEngine = server.start(wait = false)
            val port = applicationEngine.resolvedConnectors().first().port

            val objectMapper = jacksonObjectMapper()
            val client = HttpClient(CIO) { install(WebSockets) }

            try {
                // Script compilation warms up the Kotlin scripting host, which can take
                // a while on first use - hence the generous timeout.
                withTimeout(3.minutes) {
                    client.webSocket("ws://localhost:$port/stream/stacks") {
                        val bundle = StackBundle(
                            script = """
                                stack {
                                   val sales = resources.readText("data/sales.csv")
                                   http { get("/sales") { call -> call.respondText(sales) } }
                                }
                            """.trimIndent(),
                            resources = mapOf(
                                "data/sales.csv" to ResourceContent.text("Title,Tickets\nA New Hope,249\n")
                            )
                        )
                        send(
                            Frame.Text(
                                objectMapper.writeValueAsString(
                                    UpdateStackRSocketRequest(bundles = mapOf("films" to bundle))
                                )
                            )
                        )

                        awaitRunning(objectMapper)
                    }
                }

                val httpComponent = stackRunner.http.single()
                httpComponent.currentState.state shouldBe ComponentState.Running
                HttpClient().use { httpClient ->
                    httpClient.get("${httpComponent.baseUrl}/sales").bodyAsText() shouldBe
                        "Title,Tickets\nA New Hope,249\n"
                }
            } finally {
                stackRunner.shutDownAll()
                client.close()
                applicationEngine.stop()
            }
        }

        it("reports a bundle that would escape its directory as a compilation error") {
            val stackRunner = StackRunner()
            val server = NebulaServer(port = 0, stackExecutor = stackRunner)
            val applicationEngine = server.start(wait = false)
            val port = applicationEngine.resolvedConnectors().first().port

            val objectMapper = jacksonObjectMapper()
            val client = HttpClient(CIO) { install(WebSockets) }

            try {
                withTimeout(3.minutes) {
                    client.webSocket("ws://localhost:$port/stream/stacks") {
                        val bundle = StackBundle(
                            script = "stack {}",
                            resources = mapOf("../escaped.txt" to ResourceContent.text("pwned"))
                        )
                        send(
                            Frame.Text(
                                objectMapper.writeValueAsString(
                                    UpdateStackRSocketRequest(bundles = mapOf("films" to bundle))
                                )
                            )
                        )

                        // A hostile bundle must come back as an ordinary rejection on the
                        // socket, not as a dropped connection.
                        val event = objectMapper.readValue<StackStateEvent>(nextTextFrame())
                        event.stackName shouldBe "films"
                        event.compilationErrors.shouldNotBeEmpty()
                    }
                }
                stackRunner.stacks.containsKey("films") shouldBe false
            } finally {
                stackRunner.shutDownAll()
                client.close()
                applicationEngine.stop()
            }
        }
    }
})

/**
 * Reads frames until the stack reports Running. Lifecycle events arrive one per
 * transition (NotStarted, Starting, Running), so a test that read a single frame
 * would be racing the component's startup.
 *
 * Reads the frames as a tree rather than as [StackStateEvent]: the lifecycle
 * events inside a running stack's state don't round-trip through Jackson (the
 * `NotStartedEvent` data object has no properties to bind), and that is a
 * separate wart from the one under test here.
 */
private suspend fun DefaultClientWebSocketSession.awaitRunning(
    objectMapper: com.fasterxml.jackson.databind.ObjectMapper
) {
    while (true) {
        val event = objectMapper.readTree(nextTextFrame())
        val errors = event["compilationErrors"]
        if (errors != null && errors.size() > 0) {
            error("The bundle was rejected: $errors")
        }
        if (event["stateCounts"]?.has(ComponentState.Running.name) == true) return
    }
}

private suspend fun DefaultClientWebSocketSession.nextTextFrame(): String {
    while (true) {
        val frame = incoming.receive()
        if (frame is Frame.Text) return frame.readText()
    }
}
