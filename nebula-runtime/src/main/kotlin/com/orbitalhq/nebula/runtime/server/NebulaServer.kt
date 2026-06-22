package com.orbitalhq.nebula.runtime.server

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.NebulaStack
import com.orbitalhq.nebula.NebulaStackWithSource
import com.orbitalhq.nebula.StackName
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.runtime.NebulaScriptExecutor
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
                compilationErrors = emptyList()
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
                                    exception.errors.map { it.toCompilationErrorDto() }
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

                        logger.info { "Received updated stack submission: \n$payloadJson" }
                        val updateStacksRequest =
                            objectMapper.readValue<UpdateStackRSocketRequest>(payloadJson)

                        val stackMap = compile(updateStacksRequest, call.hostConfig())
                        val eventStreams = stackMap.map { (name, stack) ->
                            stackExecutor.submit(stack, name, startAsync = true)
                        }
                        Flux.merge(eventStreams)
                            .subscribe { event ->
                                logger.info { "Emitting stack status event for stack ${event.stackName}" }
                                runBlocking {
                                    val stackStatusJson = objectMapper.writeValueAsString(event)
                                    send(Frame.Text(stackStatusJson))
                                }

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

    private fun compile(updateStacksRequest: UpdateStackRSocketRequest, hostConfig: HostConfig): Map<StackName, NebulaStackWithSource> {
        return updateStacksRequest.stacks.mapValues { (key, stackScript) ->
            scriptExecutor.toStackWithSource(stackScript, hostConfig).withName(key)
        }
    }

}

data class StackEventStreamRequest(val stackId: StackName)

typealias StackScript = String

data class UpdateStackRSocketRequest(val stacks: Map<StackName, StackScript>)

fun ApplicationCall.hostConfig():HostConfig {
    return HostConfig(listOf(this.request.host()))
}