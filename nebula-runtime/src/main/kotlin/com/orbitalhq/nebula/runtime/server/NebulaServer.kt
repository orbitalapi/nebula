package com.orbitalhq.nebula.runtime.server

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.NebulaStack
import com.orbitalhq.nebula.NebulaStackWithSource
import com.orbitalhq.nebula.StackName
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.core.StackBundle
import com.orbitalhq.nebula.core.StackStateEvent
import com.orbitalhq.nebula.runtime.NebulaScriptExecutor
import com.orbitalhq.nebula.tools.ToolNotAvailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.rsocket.kotlin.ktor.server.RSocketSupport
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import reactor.core.publisher.Flux
import reactor.core.publisher.Hooks

class NebulaServer(
    private val port: Int = 8999,
    private val scriptExecutor: NebulaScriptExecutor = NebulaScriptExecutor(),
    private val config: NebulaConfig = NebulaConfig(),
    private val stackExecutor: StackRunner = StackRunner(config)
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    init {
        Hooks.onErrorDropped { throwable ->
            logger.error("Unhandled error in Reactor pipeline", throwable)
        }

    }

    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    // Submissions that failed to compile, keyed by stack name. Surfaced in the
    // admin snapshot until a valid version replaces them (or they're deleted).
    private val failedSubmissions = ConcurrentHashMap<StackName, FailedSubmission>()

    private fun buildAdminSnapshot(): List<AdminStackView> {
        val compiled = stackExecutor.snapshot().map { event ->
            AdminStackView(
                name = event.stackName,
                stackState = event,
                source = stackExecutor.sourceFor(event.stackName) ?: "",
                compilationErrors = emptyList(),
                tools = stackExecutor.tools(event.stackName)
            )
        }
        val compiledNames = compiled.map { it.name }.toSet()
        val failed = failedSubmissions.values
            .filter { it.name !in compiledNames }
            .map { AdminStackView(it.name, null, it.source, it.compilationErrors) }
        return compiled + failed
    }

    fun start(wait: Boolean = true): NettyApplicationEngine {
        return embeddedServer(Netty, port = port) {
            install(WebSockets)
            install(RSocketSupport)
            install(ContentNegotiation) {
                jackson {
                    findAndRegisterModules()
                    configure(SerializationFeature.INDENT_OUTPUT, true)
                    configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                }
            }

            routing {
                route("/health") {
                    get {
                        call.respondText("OK")
                    }
                }
                route("/stacks") {
                    // Create a stack without an id -- an id is assigned
                    post {
                        val script = call.receiveText()
                        val stack = scriptExecutor.toStackWithSource(script, call.hostConfig())
                        stackExecutor.submit(stack, startAsync = true)
                        call.respond(stack.name)
                    }
                    // Submit a stack together with the files it reads at startup.
                    // Additive: the script-only routes above are untouched, and a
                    // bundle carrying no resources behaves identically to them.
                    post("/bundle") {
                        val bundle = call.receive<StackBundle>()
                        // The name is assigned by the runner, but the unpacker wants
                        // something to name the directory after before we have one.
                        submitBundle(call, "unnamed-stack", bundle) { stack ->
                            stackExecutor.submit(stack, startAsync = true)
                            call.respond(stack.name)
                        }
                    }
                    put("/bundle/{id}") {
                        val id = call.parameters["id"] ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            "Missing or malformed id"
                        )
                        val bundle = call.receive<StackBundle>()
                        submitBundle(call, id, bundle) { stack ->
                            val named = stack.withName(id)
                            stackExecutor.submit(named, id)
                            call.respond(named.name)
                        }
                    }
                    put("/{id}") {
                        val id = call.parameters["id"] ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            "Missing or malformed id"
                        )
                        val script = call.receiveText()
                        val stack = scriptExecutor.toStackWithSource(script, call.hostConfig()).let { stack ->
                            stack.withName(id)
                        }
                        stackExecutor.submit(stack)
                        call.respond(stack.name)
                    }

                    get {
                        call.respond(stackExecutor.stateState)
                    }

                    delete("/{id}") {
                        val id = call.parameters["id"] ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            "Missing or malformed id"
                        )
                        stackExecutor.shutDown(id)
                        call.respond(stackExecutor.stateState)
                    }
                }
                // Admin API for the management UI.
                // Distinct from the Orbital-facing /stacks + /stream/stacks contract above,
                // which is left untouched.
                route("/api/stacks") {
                    // Unified snapshot: compiled stacks (with live state) plus any
                    // submissions that failed to compile (with their source + errors).
                    get {
                        call.respond(buildAdminSnapshot())
                    }
                    // Submit a stack from the admin UI. On compilation failure the
                    // submission (source + errors) is recorded so it appears in the
                    // snapshot, and is replaced once a valid version is submitted
                    // under the same name.
                    put("/{id}") {
                        val id = call.parameters["id"] ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            "Missing or malformed id"
                        )
                        val script = call.receiveText()
                        scriptExecutor.compileToStackWithSource(script, call.hostConfig()).fold(
                            { exception ->
                                val failure = FailedSubmission(
                                    id,
                                    script,
                                    exception.errors.map { it.toCompilationError() }
                                )
                                failedSubmissions[id] = failure
                                call.respond(HttpStatusCode.UnprocessableEntity, failure)
                            },
                            { stackWithSource ->
                                failedSubmissions.remove(id)
                                stackExecutor.submit(stackWithSource.withName(id), startAsync = true)
                                call.respond(buildAdminSnapshot())
                            }
                        )
                    }
                    // Start a whole stack that was previously stopped.
                    post("/{id}/start") {
                        handleStackAction(call) { id -> stackExecutor.startStack(id) }
                    }
                    // Stop a whole stack (components stopped, stack stays listed as Stopped).
                    post("/{id}/stop") {
                        handleStackAction(call) { id -> stackExecutor.shutDown(id) }
                    }
                    // Remove a stack entirely (stopping it first), or clear a failed submission.
                    delete("/{id}") {
                        val id = call.parameters["id"] ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            "Missing or malformed id"
                        )
                        failedSubmissions.remove(id)
                        stackExecutor.removeStack(id)
                        call.respond(buildAdminSnapshot())
                    }
                    // Stop a single component, leaving the rest of the stack running.
                    post("/{id}/components/{componentId}/stop") {
                        handleComponentAction(call) { id, componentId ->
                            stackExecutor.stopComponent(id, componentId)
                        }
                    }
                    // (Re)start a single component.
                    post("/{id}/components/{componentId}/start") {
                        handleComponentAction(call) { id, componentId ->
                            stackExecutor.startComponent(id, componentId)
                        }
                    }
                    // Launch one of a component's tools (eg: an admin console). Returns
                    // straight away with the tool Starting - poll the snapshot for when it's up.
                    post("/{id}/components/{componentId}/tools/{toolId}/launch") {
                        handleToolAction(call) { id, componentId, toolId ->
                            stackExecutor.launchTool(id, componentId, toolId)
                        }
                    }
                    post("/{id}/components/{componentId}/tools/{toolId}/stop") {
                        handleToolAction(call) { id, componentId, toolId ->
                            stackExecutor.stopTool(id, componentId, toolId)
                        }
                    }
                }
                // Live log stream for a stack.
                webSocket("/api/stacks/{id}/logs") {
                    val id = call.parameters["id"] ?: return@webSocket close(
                        CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing id")
                    )
                    val disposable = try {
                        stackExecutor.logs(id).subscribe { message ->
                            runBlocking {
                                send(Frame.Text(objectMapper.writeValueAsString(message)))
                            }
                        }
                    } catch (e: Exception) {
                        return@webSocket close(
                            CloseReason(CloseReason.Codes.CANNOT_ACCEPT, e.message ?: "Stack not found")
                        )
                    }
                    try {
                        incoming.consumeEach { /* ignore inbound frames */ }
                    } finally {
                        disposable.dispose()
                    }
                }
                // Live state-event stream for a stack.
                webSocket("/api/stacks/{id}/events") {
                    val id = call.parameters["id"] ?: return@webSocket close(
                        CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing id")
                    )
                    val disposable = try {
                        stackExecutor.stackEvents(id).subscribe { event ->
                            runBlocking {
                                send(Frame.Text(objectMapper.writeValueAsString(event)))
                            }
                        }
                    } catch (e: Exception) {
                        return@webSocket close(
                            CloseReason(CloseReason.Codes.CANNOT_ACCEPT, e.message ?: "Stack not found")
                        )
                    }
                    try {
                        incoming.consumeEach { /* ignore inbound frames */ }
                    } finally {
                        disposable.dispose()
                    }
                }
                webSocket("/stream/stacks") {
                    val call = call
                    incoming.consumeEach { frame ->
                        require(frame is Frame.Text) { "Only text frames supported" }
                        val payloadJson = frame.readText()

                        // Any failure handling a submission must not tear down the socket —
                        // the client would see nothing but a dropped connection.
                        try {
                            val updateStacksRequest =
                                objectMapper.readValue<UpdateStackRSocketRequest>(payloadJson)
                            // Just the names - the scripts themselves are far too noisy to log.
                            val submitted = updateStacksRequest.allBundles().keys
                            logger.info { "Received submission of ${submitted.size} stack(s): ${submitted.joinToString()}" }

                            // Compile each stack independently: a broken script must not block
                            // the other stacks in the submission. Failures are reported back to
                            // the client as a StackStateEvent carrying the compilation errors,
                            // and recorded so the admin snapshot shows them too.
                            val eventStreams = updateStacksRequest.allBundles().mapNotNull { (name, bundle) ->
                                scriptExecutor.compileBundle(name, bundle, call.hostConfig()).fold(
                                    { compilationException ->
                                        val errors = compilationException.errors.map { it.toCompilationError() }
                                        logger.warn { "Stack $name failed to compile: ${errors.joinToString { it.message }}" }
                                        failedSubmissions[name] = FailedSubmission(name, bundle.script, errors)
                                        send(
                                            Frame.Text(
                                                objectMapper.writeValueAsString(
                                                    StackStateEvent(
                                                        stackName = name,
                                                        stateCounts = emptyMap(),
                                                        stackState = emptyMap(),
                                                        compilationErrors = errors
                                                    )
                                                )
                                            )
                                        )
                                        null
                                    },
                                    { stackWithSource ->
                                        failedSubmissions.remove(name)
                                        stackExecutor.submit(stackWithSource.withName(name), name, startAsync = true)
                                    }
                                )
                            }
                            Flux.merge(eventStreams)
                                .subscribe { event ->
                                    logger.info { "Emitting stack status event for stack ${event.stackName}" }
                                    runBlocking {
                                        val stackStatusJson = objectMapper.writeValueAsString(event)
                                        send(Frame.Text(stackStatusJson))
                                    }

                                }
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to process stack submission" }
                        }
                    }
                }
                // Serve the management UI (bundled into the jar under resources/web).
                // Declared last so its catch-all fallback never shadows the API routes above.
                singlePageApplication {
                    useResources = true
                    filesPath = "web"
                }
            }
        }.start(wait = wait)
    }

    /**
     * Compiles a submitted bundle and hands the compiled stack to [onCompiled],
     * or answers 422 with the compilation errors.
     *
     * Bundle problems (a resource that escapes the bundle directory, a file over
     * the size limit) arrive here as compilation errors, so the caller gets the
     * same shaped response whether the script or its resources were the problem.
     */
    private suspend fun submitBundle(
        call: ApplicationCall,
        stackName: StackName,
        bundle: StackBundle,
        onCompiled: suspend (NebulaStackWithSource) -> Unit
    ) {
        scriptExecutor.compileBundle(stackName, bundle, call.hostConfig()).fold(
            { exception ->
                val failure = FailedSubmission(
                    stackName,
                    bundle.script,
                    exception.errors.map { it.toCompilationError() }
                )
                failedSubmissions[stackName] = failure
                call.respond(HttpStatusCode.UnprocessableEntity, failure)
            },
            { stack ->
                failedSubmissions.remove(stackName)
                onCompiled(stack)
            }
        )
    }

    private suspend fun handleStackAction(
        call: ApplicationCall,
        action: (id: String) -> Unit
    ) {
        val id = call.parameters["id"]
            ?: return call.respond(HttpStatusCode.BadRequest, "Missing or malformed id")
        try {
            action(id)
        } catch (e: IllegalStateException) {
            return call.respond(HttpStatusCode.NotFound, e.message ?: "Not found")
        }
        call.respond(buildAdminSnapshot())
    }

    private suspend fun handleToolAction(
        call: ApplicationCall,
        action: (id: String, componentId: String, toolId: String) -> Any?
    ) {
        val id = call.parameters["id"]
            ?: return call.respond(HttpStatusCode.BadRequest, "Missing or malformed id")
        val componentId = call.parameters["componentId"]
            ?: return call.respond(HttpStatusCode.BadRequest, "Missing or malformed componentId")
        val toolId = call.parameters["toolId"]
            ?: return call.respond(HttpStatusCode.BadRequest, "Missing or malformed toolId")
        val result = try {
            action(id, componentId, toolId)
        } catch (e: ToolNotAvailableException) {
            return call.respond(HttpStatusCode.Conflict, e.message ?: "Tool not available")
        } catch (e: IllegalStateException) {
            return call.respond(HttpStatusCode.NotFound, e.message ?: "Not found")
        }
        if (result == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respond(result)
        }
    }

    private suspend fun handleComponentAction(
        call: ApplicationCall,
        action: (id: String, componentId: String) -> Unit
    ) {
        val id = call.parameters["id"]
            ?: return call.respond(HttpStatusCode.BadRequest, "Missing or malformed id")
        val componentId = call.parameters["componentId"]
            ?: return call.respond(HttpStatusCode.BadRequest, "Missing or malformed componentId")
        try {
            action(id, componentId)
        } catch (e: IllegalStateException) {
            return call.respond(HttpStatusCode.NotFound, e.message ?: "Not found")
        }
        val snapshot = stackExecutor.snapshot(id)
        if (snapshot == null) {
            call.respond(HttpStatusCode.NotFound, "Stack $id not found")
        } else {
            call.respond(snapshot)
        }
    }

}

data class StackEventStreamRequest(val stackId: StackName)

typealias StackScript = String

/**
 * A submission of one or more stacks over `/stream/stacks`.
 *
 * Accepts both shapes:
 *
 *  - `{"stacks": {"name": "<script>"}}` - the original, script-only message.
 *    Still what Orbital sends when a project ships no resource files.
 *  - `{"bundles": {"name": {"script": "...", "resources": {...}}}}` - a stack
 *    plus the files that travel with it.
 *
 * Both fields default to empty, so an old client's message deserialises without
 * a `bundles` field and a new client's without a `stacks` field. Orbital only
 * sends `bundles` when a project actually has resources, which is what lets a
 * new Orbital keep talking to a Nebula server that predates this message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class UpdateStackRSocketRequest(
    val stacks: Map<StackName, StackScript> = emptyMap(),
    val bundles: Map<StackName, StackBundle> = emptyMap()
) {
    /**
     * The submission normalised to bundles. A script-only entry is a bundle with
     * no resources, so the server has a single code path for both.
     */
    fun allBundles(): Map<StackName, StackBundle> =
        stacks.mapValues { (_, script) -> StackBundle.scriptOnly(script) } + bundles
}

fun ApplicationCall.hostConfig():HostConfig {
    return HostConfig(listOf(this.request.host()))
}