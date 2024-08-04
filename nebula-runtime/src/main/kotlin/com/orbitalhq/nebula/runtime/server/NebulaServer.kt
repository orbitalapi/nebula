package com.orbitalhq.nebula.runtime.server

import com.fasterxml.jackson.databind.SerializationFeature
import com.orbitalhq.nebula.NebulaStack
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.runtime.NebulaScriptExecutor
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class NebulaServer(
    private val port: Int = 8999,
    private val scriptExecutor: NebulaScriptExecutor = NebulaScriptExecutor(),
    private val stackExecutor: StackRunner = StackRunner()
) {
    fun start() {
        embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                jackson {
                    configure(SerializationFeature.INDENT_OUTPUT, true)
                }
            }

            routing {
                route("/stack") {
                    post {
                        val script = call.receiveText()
                        val stack = scriptExecutor.toStack(script)
                        stackExecutor.submit(stack)
                        call.respond(stack.name)
                    }

                    put("/{id}") {
                        val id = call.parameters["id"] ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            "Missing or malformed id"
                        )
                        val script = call.receiveText()
                        val stack = scriptExecutor.toStack(script).let {
                            NebulaStack(name = id, initialComponents = it.components)
                        }
                        stackExecutor.submit(stack)
                        call.respond(stack.name)
                    }

                    get {
                        call.respond(stackExecutor.stackNames)
                    }

                    delete("/{id}") {
                        val id = call.parameters["id"] ?: return@delete call.respond(
                            HttpStatusCode.BadRequest,
                            "Missing or malformed id"
                        )
                        stackExecutor.shutDown(id)
                        call.respond(stackExecutor.stackNames)
                    }
                }
            }
        }.start(wait = true)
    }

}