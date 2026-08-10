package com.orbitalhq.nebula.runtime.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.StackStateEvent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import reactor.core.publisher.Flux
import kotlin.time.Duration.Companion.minutes

/**
 * Covers the /stream/stacks contract for scripts that fail to compile:
 * the submission must produce a StackStateEvent carrying the compilation
 * errors (rather than silently killing the socket), and the same connection
 * must remain usable for a subsequent, valid submission.
 */
class StreamStacksCompilationErrorTest : DescribeSpec({

    describe("/stream/stacks compilation failures") {

        it("reports compilation errors back over the socket and keeps the connection alive") {
            val runningEvent = StackStateEvent(
                stackName = "my-stack",
                stateCounts = mapOf(ComponentState.Running to 1),
                stackState = emptyMap()
            )
            val mockExecutor: StackRunner = mock {}
            whenever(mockExecutor.submit(any(), eq("my-stack"), eq(true))).thenReturn(Flux.just(runningEvent))

            val server = NebulaServer(port = 0, stackExecutor = mockExecutor)
            val applicationEngine = server.start(wait = false)
            val port = applicationEngine.resolvedConnectors().first().port

            val objectMapper = jacksonObjectMapper()
            val client = HttpClient(CIO) {
                install(WebSockets)
            }

            try {
                // Script compilation warms up the Kotlin scripting host, which can take
                // a while on first use — hence the generous timeout.
                withTimeout(3.minutes) {
                    client.webSocket("ws://localhost:$port/stream/stacks") {
                        send(Frame.Text(objectMapper.writeValueAsString(
                            UpdateStackRSocketRequest(mapOf("my-stack" to "this is not a valid nebula script"))
                        )))

                        val failureEvent = objectMapper.readValue<StackStateEvent>(nextTextFrame())
                        failureEvent.stackName shouldBe "my-stack"
                        failureEvent.compilationErrors.shouldNotBeEmpty()
                        failureEvent.stackState.shouldBeEmpty()

                        // The socket must survive the failure: a corrected script submitted on
                        // the same connection compiles, is submitted, and its state is relayed.
                        send(Frame.Text(objectMapper.writeValueAsString(
                            UpdateStackRSocketRequest(mapOf("my-stack" to "stack {}"))
                        )))

                        val recoveredEvent = objectMapper.readValue<StackStateEvent>(nextTextFrame())
                        recoveredEvent.stackName shouldBe "my-stack"
                        recoveredEvent.compilationErrors shouldBe emptyList()
                    }
                }
                verify(mockExecutor).submit(any(), eq("my-stack"), eq(true))
            } finally {
                client.close()
                applicationEngine.stop()
            }
        }
    }
})

private suspend fun DefaultClientWebSocketSession.nextTextFrame(): String {
    while (true) {
        val frame = incoming.receive()
        if (frame is Frame.Text) return frame.readText()
    }
}
