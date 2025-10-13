package com.orbitalhq.nebula.logging

import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.utils.logIfFailed
import lang.taxi.utils.log
import mu.KotlinLogging
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

class StackLogStream(private val bufferSize: Int = 1000) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    fun attachLogStreams(components: List<InfrastructureComponent<*>>) {
        components.forEach { component ->
            component.logStream.flux.subscribe { logMessage ->
                sink.tryEmitNext(logMessage).logIfFailed("Failed to emit LogEvent from Stack", logger)
            }
        }
    }

    private val sink = Sinks.many().multicast().onBackpressureBuffer<LogMessage>(bufferSize)
    val logMessages: Flux<LogMessage> = sink.asFlux()


}