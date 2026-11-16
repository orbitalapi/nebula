package com.orbitalhq.nebula.runtime.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.StackStateEvent
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import kotlin.time.Duration.Companion.minutes

/**
 * A stack's state events are replayed to every new subscriber, so a submission can produce
 * more events at once than the socket's outgoing buffer holds. Relaying them must not stall
 * the connection: Orbital resubmits the full set of stacks each time a workspace changes,
 * and a stalled socket leaves later stacks unsubmitted and earlier ones without their
 * Running event (and so without their env variables).
 */
class StreamStacksBackpressureTest : DescribeSpec({

    describe("/stream/stacks with many state events") {

        it("relays every event and keeps reading submissions") {
            val eventCount = 50
            val events = Sinks.many().replay().all<StackStateEvent>()
            (1..eventCount).forEach { i ->
                events.tryEmitNext(
                    StackStateEvent(
                        stackName = "my-stack",
                        stateCounts = mapOf(ComponentState.Running to i),
                        stackState = emptyMap()
                    )
                )
            }
            val otherStackEvent = StackStateEvent(
                stackName = "other-stack",
                stateCounts = mapOf(ComponentState.Running to 1),
                stackState = emptyMap()
            )
            val mockExecutor: StackRunner = mock {}
            whenever(mockExecutor.submit(any(), eq("my-stack"), eq(true))).thenAnswer { events.asFlux() }
            whenever(mockExecutor.submit(any(), eq("other-stack"), eq(true))).thenReturn(Flux.just(otherStackEvent))

            val server = NebulaServer(port = 0, stackExecutor = mockExecutor)
            val applicationEngine = server.start(wait = false)
            val port = applicationEngine.resolvedConnectors().first().port

            val objectMapper = jacksonObjectMapper()
            val client = HttpClient(CIO) {
                install(WebSockets)
            }

            try {
                // Generous for the scripting host's warm-up, but a stalled socket fails here
                // rather than hanging the build
                withTimeout(1.minutes) {
                    client.webSocket("ws://localhost:$port/stream/stacks") {
                        send(Frame.Text(objectMapper.writeValueAsString(
                            UpdateStackRSocketRequest(mapOf("my-stack" to "stack {}"))
                        )))
                        val firstEvents = (1..eventCount).map {
                            objectMapper.readValue<StackStateEvent>(nextFrameText())
                        }
                        firstEvents.map { it.stateCounts[ComponentState.Running] } shouldBe (1..eventCount).toList()

                        // Orbital resubmits every stack when one is added: the server must still
                        // be reading, and relay the new stack's state.
                        send(Frame.Text(objectMapper.writeValueAsString(
                            UpdateStackRSocketRequest(mapOf("my-stack" to "stack {}", "other-stack" to "stack {}"))
                        )))
                        val resubmissionEvents = (1..eventCount + 1).map {
                            objectMapper.readValue<StackStateEvent>(nextFrameText())
                        }
                        resubmissionEvents.count { it.stackName == "other-stack" } shouldBe 1
                        resubmissionEvents.count { it.stackName == "my-stack" } shouldBe eventCount
                    }
                }
            } finally {
                client.close()
                applicationEngine.stop()
            }
        }
    }
})

private suspend fun DefaultClientWebSocketSession.nextFrameText(): String {
    while (true) {
        val frame = incoming.receive()
        if (frame is Frame.Text) return frame.readText()
    }
}
