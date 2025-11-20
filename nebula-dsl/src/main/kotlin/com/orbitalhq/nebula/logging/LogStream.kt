package com.orbitalhq.nebula.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.utils.logIfFailed
import mu.KotlinLogging

import org.slf4j.LoggerFactory
import org.testcontainers.containers.Container
import org.testcontainers.containers.output.OutputFrame
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Instant
import java.util.function.Consumer
import kotlin.reflect.KClass

// Marker alias.
// Pass either KClass, Class, or String
typealias LoggerName = Any

class LogStream(
    private val componentName: ComponentName,
    private val bufferSize: Int = 1000,
    private val slf4jLoggerNames: List<LoggerName> = emptyList()
) {

    private val sink: Sinks.Many<LogMessage> = Sinks.many().replay().limit<LogMessage>(bufferSize)
    val flux: Flux<LogMessage> = sink.asFlux()
    private val appenders = mutableListOf<LogStreamAppender>()

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    init {
        attachSlf4JLogListeners()
    }

    private fun attachSlf4JLogListeners() {
        slf4jLoggerNames.forEach { javaLoggerName ->
            val loggerName = when (javaLoggerName) {
                is KClass<*> -> javaLoggerName.qualifiedName ?: javaLoggerName.simpleName ?: "unknown"
                is Class<*> -> javaLoggerName.name
                is String -> javaLoggerName
                else -> error("LoggerName passed to LogStream must be either KClass, Class or String, but found ${javaLoggerName::class.simpleName}")
            }
            attachToSlf4jLogger(loggerName)
        }
    }

    private fun attachToSlf4jLogger(loggerName: String) {
        val slf4jLogger = LoggerFactory.getLogger(loggerName)

        if (slf4jLogger is Logger) {
            val appender = LogStreamAppender(sink, loggerName, componentName)
            appender.start()
            slf4jLogger.addAppender(appender)
            appenders.add(appender)
            logger.debug { "Attached LogStream appender to logger: $loggerName" }
        } else {
            logger.warn { "Could not attach appender to logger $loggerName - not a Logback logger" }
        }
    }

    fun <SELF : Container<SELF>> attachToUnstartedContainer(
        container: Container<SELF>,
        containerName: String
    ): Container<SELF> {
        return container.withLogConsumer(ContainerLogStream(sink, containerName))
    }

    /**
     * Attaches to an already running container.
     * If the container is not yet started, use attachToUnstartedContainer
     */
    fun attachToRunningContainer(container: Container<*>, containerName: String) {
        container.followOutput(ContainerLogStream(sink, containerName))
    }

    fun close() {
        appenders.forEach { appender ->
            appender.stop()
            val slf4jLogger = LoggerFactory.getLogger(appender.loggerName)
            if (slf4jLogger is Logger) {
                slf4jLogger.detachAppender(appender)
            }
        }
        appenders.clear()
    }
}

private class ContainerLogStream(private val sink: Sinks.Many<LogMessage>, private val containerName: String) :
    Consumer<OutputFrame> {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override fun accept(outputFrame: OutputFrame) {
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
                .logIfFailed("Failed to emit log message for container $containerName", logger)
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


private class LogStreamAppender(
    private val sink: Sinks.Many<LogMessage>,
    val loggerName: String,
    val containerName: String
) : AppenderBase<ILoggingEvent>() {

    override fun append(event: ILoggingEvent) {
        val streamKind = when {
            event.level.isGreaterOrEqual(Level.ERROR) -> StreamKind.STDERR
            else -> StreamKind.STDOUT
        }

        val message = LogMessage(
            containerName = containerName,
            streamKind = streamKind,
            message = "${event.level}: ${event.formattedMessage}",
            timestamp = Instant.ofEpochMilli(event.timeStamp)
        )

        sink.tryEmitNext(message)
            .logIfFailed(
                "Failed to emit log message for logger $loggerName",
                KotlinLogging.logger {})
    }
}
