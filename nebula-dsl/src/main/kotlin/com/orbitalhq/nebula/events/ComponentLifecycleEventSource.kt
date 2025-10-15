package com.orbitalhq.nebula.events

import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.LifecycleEventWithMessage
import com.orbitalhq.nebula.core.LifecycleUpdatedEvent
import com.orbitalhq.nebula.core.NotStartedEvent
import com.orbitalhq.nebula.logging.LogStream
import io.github.oshai.kotlinlogging.KotlinLogging
import org.testcontainers.containers.GenericContainer
import reactor.core.publisher.Sinks

class ComponentLifecycleEventSource(private val initialState: ComponentLifecycleEvent = NotStartedEvent) {
    private val sink = Sinks.many().replay().latest<ComponentLifecycleEvent>()

    companion object {
        private val logger = KotlinLogging.logger {}
    }
    val events = sink.asFlux()
//        .distinct()
        .doOnNext { event ->
            currentState = event
        }

    init {
        emitNext(initialState)
    }

    private fun emitNext(event:ComponentLifecycleEvent) {
        currentState = event
        val emitResult = sink.tryEmitNext(event)
        if (emitResult.isFailure) {
            logger.warn { "Failed to emit ComponentLifecycleEvent - $emitResult" }
        }

    }

    var currentState: ComponentLifecycleEvent = NotStartedEvent
        private set

    fun starting() {
        emitNext(LifecycleUpdatedEvent(ComponentState.Starting))
    }

    fun running() {
        emitNext(LifecycleUpdatedEvent(ComponentState.Running))
    }

    fun stopping() {
        emitNext(LifecycleUpdatedEvent(ComponentState.Stopping))
    }

    fun stopped() {
        emitNext(LifecycleUpdatedEvent(ComponentState.Stopped))
    }

    fun failed(message: String) {
        emitNext(LifecycleEventWithMessage(ComponentState.Failed, message))
    }

    fun startContainerAndEmitEvents(container: GenericContainer<*>, logStream: LogStream? = null,  containerName: String? = null, initStep:() -> Unit = {}) {
        starting()
        try {
            if (logStream != null) {
                require(containerName != null) { "You must pass the container name when passing a logStream"}
                logStream.attachToUnstartedContainer(container,containerName)
            }
            container.start()
            initStep()
            running()
        } catch (e: Exception) {
            failed(e.message ?: e::class.simpleName!!)
        }
    }

    fun stopContainerAndEmitEvents(container: GenericContainer<*>) {
        stopping()
        try {
            container.stop()
        } catch (e: Exception) {
            failed(e.message ?: e::class.simpleName!!)
        }
        stopped()
    }
}
