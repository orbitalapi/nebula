package com.orbitalhq.nebula.runtime.server

import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.StackStateEvent
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.rsocket.kotlin.ktor.client.RSocketSupport
import io.rsocket.kotlin.ktor.client.rSocket
import io.rsocket.kotlin.payload.buildPayload
import io.rsocket.kotlin.payload.data
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import reactor.core.publisher.Sinks
import kotlin.time.Duration.Companion.seconds

class NebulaServerTest : DescribeSpec({
    xdescribe("Nebula server") {
        describe("rsockets") {
            it("should consume lifecycle events") {
                val portPromise = CompletableDeferred<Int>()
                val mockExecutor: StackRunner = mock { }
                val eventSink = Sinks.many().unicast().onBackpressureBuffer<StackStateEvent>()
                whenever(mockExecutor.stackEvents(any())).thenReturn(eventSink.asFlux())
                val server = NebulaServer(
                    port = 0,
                    stackExecutor = mockExecutor
                )
                val applicationEngine = server.start(wait = false)
                val port = applicationEngine.resolvedConnectors().first().port


                val client = HttpClient(CIO) {
                    install(WebSockets)
                    install(RSocketSupport)
                }
                val rsocket = client.rSocket("ws://localhost:$port/events")
                val receivedEvents = mutableListOf<String>()
                val job = launch {
                    rsocket.requestStream(buildPayload { data("""{ "stackId" : "abc" }""") })
                        .collect { payload ->
                            receivedEvents.add(payload.data.readText())
                        }
                }

                delay(1.seconds)
                eventSink.tryEmitNext(
                    StackStateEvent(
                        "MyStack",
                        mapOf(ComponentState.Running to 1),
                        emptyMap()
                    )
                )

                eventually(5.seconds) {
                    receivedEvents.shouldHaveSize(1)
                }

                job.cancel()
                client.close()
                applicationEngine.stop()
            }
        }
    }
})