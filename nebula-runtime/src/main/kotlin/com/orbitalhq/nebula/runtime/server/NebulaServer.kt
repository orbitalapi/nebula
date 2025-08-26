package com.orbitalhq.nebula.runtime.server

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
                        val stack = scriptExecutor.toStackWithSource(script)
                        stackExecutor.submit(stack, startAsync = true)
                        call.respond(stack.name)
                    }
                    put("/{id}") {
                        val id = call.parameters["id"] ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            "Missing or malformed id"
                        )
                        val script = call.receiveText()
                        val stack = scriptExecutor.toStackWithSource(script).let { stack ->
                            stack.withName(id)
                        }
                        stackExecutor.submit(stack)
                        call.respond(stack.name)
                    }

                    get {
                        val j = objectMapper
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
                webSocket("/stream/stacks") {
                    incoming.consumeEach { frame ->
                        require(frame is Frame.Text) { "Only text frames supported" }
                        val payloadJson = frame.readText()

                        logger.info { "Received updated stack submission: \n$payloadJson" }
                        val updateStacksRequest =
                            objectMapper.readValue<UpdateStackRSocketRequest>(payloadJson)

                        val stackMap = compile(updateStacksRequest)
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
            }
        }.start(wait = wait)
    }

    private fun compile(updateStacksRequest: UpdateStackRSocketRequest): Map<StackName, NebulaStackWithSource> {
        return updateStacksRequest.stacks.mapValues { (key, stackScript) ->
            scriptExecutor.toStackWithSource(stackScript).withName(key)
        }
    }

}

data class StackEventStreamRequest(val stackId: StackName)

typealias StackScript = String

data class UpdateStackRSocketRequest(val stacks: Map<StackName, StackScript>)