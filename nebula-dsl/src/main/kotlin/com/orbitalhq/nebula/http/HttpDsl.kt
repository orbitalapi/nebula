package com.orbitalhq.nebula.http

import com.orbitalhq.nebula.InfraDsl
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.util.pipeline.*

interface HttpDsl : InfraDsl {
    fun http(port: Int = 0, dsl: HttpApiBuilder.() -> Unit): HttpExecutor {
        val builder = HttpApiBuilder(port)
        builder.dsl()
        return this.add(HttpExecutor(builder.build()))
    }
}

data class Route(
    val method: HttpMethod,
    val path: String,
    val handler: suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit
)


class HttpApiBuilder(private val port: Int = 0) {
    private val routes = mutableListOf<Route>()
    private fun addRoute(method: HttpMethod, path: String,
                         handler: suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit
    ) {
        routes.add(Route(method, path, handler))
    }

    fun get(path: String, handler:suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit
    ) {
        addRoute(HttpMethod.Get, path, handler)
    }

    fun post(path: String, handler:suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit) {
        addRoute(HttpMethod.Post, path, handler)
    }

    fun put(path: String, handler:suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit) {
        addRoute(HttpMethod.Put, path, handler)
    }

    fun delete(path: String, handler:suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit) {
        addRoute(HttpMethod.Delete, path, handler)
    }

    fun build(): HttpConfig = HttpConfig(port, routes)
}

data class HttpConfig(val port: Int, val routes: List<Route>)