package com.orbitalhq.nebula.events

import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.StackName
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.StackStateEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

class StackStateEventSource {

    companion object {
        private val logger = KotlinLogging.logger {}
    }
    private val sink = Sinks.many().replay().latest<StackStateEvent>()
    val events = sink.asFlux()

    private val componentStates = ConcurrentHashMap<InfrastructureComponent<*>, ComponentState>()

    fun listenForEvents(stackName: StackName, components:List<InfrastructureComponent<*>>) {
        // Convert each subscription so we know who emitted it
        val eventsWithEmitter = components.map { component ->
            component.lifecycleEvents.map { event -> component to event }
        }
        Flux.merge(eventsWithEmitter).subscribe { (component, event) ->
            logger.info { "==========================Event state======================" }
            logger.info { "=====  ${component.name} : ${event.state} =================" }
            logger.info { "===========================================================" }
            componentStates[component] = event.state
            val stateEvent = computeStackStateEventFor(stackName, componentStates, components)
            val emitResult = sink.tryEmitNext(stateEvent)
            if (emitResult !== Sinks.EmitResult.OK) {
                logger.error { "Failed to emit state update:  ${emitResult}" }
            }
        }
    }

}