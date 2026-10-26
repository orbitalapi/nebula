package com.orbitalhq.nebula

import com.orbitalhq.nebula.apigateway.ApiGatewayDsl
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.StackStateEvent
import com.orbitalhq.nebula.events.StackStateEventSource
import com.orbitalhq.nebula.hazelcast.HazelcastDsl
import com.orbitalhq.nebula.http.HttpDsl
import com.orbitalhq.nebula.kafka.KafkaDsl
import com.orbitalhq.nebula.logging.LogMessage
import com.orbitalhq.nebula.logging.StackLogStream
import com.orbitalhq.nebula.mongo.MongoDsl
import com.orbitalhq.nebula.resources.StackResources
import com.orbitalhq.nebula.resources.StackResourcesContext
import com.orbitalhq.nebula.s3.S3Dsl
import com.orbitalhq.nebula.sql.SqlDsl
import com.orbitalhq.nebula.taxi.TaxiPublisherDsl
import com.orbitalhq.nebula.utils.NameGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicBoolean

typealias StackName = String

data class NebulaStackWithSource(
    val stack: NebulaStack,
    val source: String,
    val hostConfig: HostConfig
) {
    fun withName(id: String):NebulaStackWithSource {
        return this.copy(stack = stack.withName(id))
    }

    val name = stack.name

    /**
     * The files shipped with this submission, unpacked and confined to their own
     * directory. [StackResources.NONE] for a script-only submission.
     */
    val resources: StackResources
        get() = stack.resources

    /**
     * Identity of a submission: the script text *plus* the content of the
     * resources shipped with it.
     *
     * [StackRunner] dedupes on this rather than on [source] alone, so editing a
     * CSV or an OpenAPI spec next to an unchanged script still replaces the
     * running stack instead of being skipped as a duplicate submission.
     */
    val submissionKey: String
        get() = source + "\u0000" + stack.resources.fingerprint
}

class NebulaStack(
    val name: StackName = NameGenerator.generateName(),
    initialComponents: List<InfrastructureComponent<*>> = emptyList(),
    /**
     * The files shipped alongside this stack's script. Bound by the executor when
     * the stack was submitted as a bundle; [StackResources.NONE] otherwise.
     */
    override val resources: StackResources = StackResources.NONE
) : InfraDsl, KafkaDsl, S3Dsl, ApiGatewayDsl, HttpDsl, SqlDsl, HazelcastDsl, MongoDsl, TaxiPublisherDsl {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    private val _components = mutableListOf<InfrastructureComponent<*>>()

    private val isStarted = AtomicBoolean(false)

    val started: Boolean
        get() {
            return isStarted.get()
        }

    init {
        initialComponents.forEach { add(it) }
    }

    fun withName(name: StackName): NebulaStack {
        return NebulaStack(name, this._components, this.resources)
    }

    private val logStream = StackLogStream()
    private val stackStateEventSource = StackStateEventSource()
    val lifecycleEvents:Flux<StackStateEvent> = stackStateEventSource.events
    val logMessages:Flux<LogMessage> = logStream.logMessages

    override fun <T : InfrastructureComponent<*>> add(component: T): T {
        if (isStarted.get()) {
            error("Cannot modify a stack after it has started")
        }
        _components.add(component)
        return component
    }

    private val listening = AtomicBoolean(false)

    /**
     * Relays the components' lifecycle events and logs to the stack's [lifecycleEvents]
     * and [logMessages]. Idempotent - a stack only needs wiring once, whether it's
     * started on submission or later on.
     */
    fun attachListeners() {
        if (listening.compareAndSet(false, true)) {
            stackStateEventSource.listenForEvents(name, components)
            logStream.attachLogStreams(components)
        }
    }

    fun startComponents(config: NebulaConfig, hostConfig: HostConfig): Map<String, ComponentInfo<out Any?>> {
        markStarted()
        attachListeners()
        return components.mapNotNull { component ->
            try {
                component.type to component.start(config, hostConfig)
            } catch (e: Exception) {
                // The component's own event source is responsible for emitting Failed
                // (which is what reaches clients); here we just stop the exception from
                // killing the stack's start thread, and let the remaining components start.
                logger.error(e) { "Component ${component.name} in stack $name failed to start" }
                null
            }
        }.toMap()
    }

    fun markStarted() {
        isStarted.set(true)
    }

    /**
     * Marks this stack as no longer running, so a later submission of the same
     * source is started rather than treated as already running.
     */
    fun markStopped() {
        isStarted.set(false)
    }

    override val components: List<InfrastructureComponent<*>>
        get() {
            return _components.toList()
        }
}

/**
 * Declares a stack.
 *
 * Picks up the resource bundle bound for the current thread by the executor, so
 * a script submitted as a bundle can read the files that shipped with it (via
 * `resources`, or by giving a relative path to any provider that reads a file)
 * without having to declare anything.
 */
fun stack(init: NebulaStack.() -> Unit): NebulaStack {
    return NebulaStack(resources = StackResourcesContext.current()).apply(init)
}

/**
 * Declares a stack with an explicit set of resources.
 *
 * Scripts don't use this - they get their bundle from the submission. It exists
 * for hosts (the CLI, tests) that build a stack in-process and want to point it
 * at a directory of files.
 */
fun stack(resources: StackResources, init: NebulaStack.() -> Unit): NebulaStack {
    return NebulaStack(resources = resources).apply(init)
}