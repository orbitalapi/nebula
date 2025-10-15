package com.orbitalhq.nebula.logging

import com.orbitalhq.nebula.utils.logIfFailed
import mu.KotlinLogging
import org.testcontainers.containers.Container
import org.testcontainers.containers.output.OutputFrame
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Instant

class LogStream(private val bufferSize: Int = 1000) {

    private val sink = Sinks.many().replay().limit<LogMessage>(bufferSize)
    val flux: Flux<LogMessage> = sink.asFlux()
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    fun attachContainer(container: Container<*>, containerName: String) {
        container.followOutput { outputFrame ->
            val message = when (outputFrame.type) {
                OutputFrame.OutputType.STDOUT -> LogMessage(
                    containerName,
                    StreamKind.STDOUT,
                    outputFrame.utf8String.trim()
                )

                OutputFrame.OutputType.STDERR -> LogMessage(
                    containerName,
                    StreamKind.STDERR,
                    outputFrame.utf8String.trim()
                )

                else -> {
                    // Don't complete. If the service gets restarted, we use the same sink.
//                    sink.tryEmitComplete()
//                        .logIfFailed("Failed to emit log stream completion event", logger)
                    null
                }
            }?.let {
                sink.tryEmitNext(it)
                    .logIfFailed("Failed to emit log message", logger)
            }

        }
    }
}


data class LogMessage(
    val containerName: String,
    val streamKind: StreamKind,
    val message: String,
    val timestamp: Instant = Instant.now()
)

enum class StreamKind {
    STDOUT,
    STDERR
}