package com.orbitalhq.nebula.http

import com.orbitalhq.nebula.InfraDsl
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.utils.NameGenerator
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.util.pipeline.*

interface HttpDsl : InfraDsl {
    fun http(port: Int = 0, componentName: String = "http", dsl: HttpApiBuilder.() -> Unit): HttpExecutor {
        val builder = HttpApiBuilder(port, componentName)
        builder.dsl()
        return this.add(HttpExecutor(builder.build()))
    }

    companion object {
        val defaultImports: List<String> = listOf(
            "io.ktor.server.request.*",
            "io.ktor.server.response.*"
        )
    }

}

data class Route(
    val method: HttpMethod,
    val path: String,
    val handler: suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit
)


class HttpApiBuilder(private val port: Int = 0, private val componentName: ComponentName) {
    private val routes = mutableListOf<Route>()
    private fun addRoute(
        method: HttpMethod, path: String,
        handler: suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit
    ) {
        routes.add(Route(method, path, handler))
    }

    fun get(
        path: String, handler: suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit
    ) {
        addRoute(HttpMethod.Get, path, handler)
    }

    fun post(path: String, handler: suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit) {
        addRoute(HttpMethod.Post, path, handler)
    }

    fun put(path: String, handler: suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit) {
        addRoute(HttpMethod.Put, path, handler)
    }

    fun delete(path: String, handler: suspend PipelineContext<Unit, ApplicationCall>.(ApplicationCall) -> Unit) {
        addRoute(HttpMethod.Delete, path, handler)
    }

    fun build(): HttpConfig = HttpConfig(port, routes, componentName)
}

data class HttpConfig(val port: Int, val routes: List<Route>, val name: String)