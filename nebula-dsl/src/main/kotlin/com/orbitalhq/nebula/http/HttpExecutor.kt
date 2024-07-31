package com.orbitalhq.nebula.http

import com.orbitalhq.nebula.ContainerInfo
import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.InfrastructureExecutor
import com.orbitalhq.nebula.NetworkInfo
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.ServerSocket

val InfrastructureExecutor.http: HttpExecutor
    get() {
        return this.component<HttpExecutor>()
    }

class HttpExecutor(private val config: HttpConfig) : InfrastructureComponent {

    private val port = if (config.port == 0) {
        findFreePort()
    } else {
        config.port
    }

    val baseUrl: String
        get() {
            return "http://localhost:$port"
        }
    lateinit var server: NettyApplicationEngine
        private set


    override fun start() {
        server = embeddedServer(Netty, port = port) {
            routing {
                config.routes.forEach { route ->
                    when (route.method) {
                        HttpMethod.Get -> get(route.path) { route.handler(this, call) }
                        HttpMethod.Post -> post(route.path) {  route.handler(this, call)  }
                        HttpMethod.Put -> put(route.path) {  route.handler(this, call)  }
                        HttpMethod.Delete -> delete(route.path) {  route.handler(this, call) }
                        else -> throw IllegalArgumentException("Unsupported HTTP method: ${route.method}")
                    }
                }
            }
        }
        server.start(wait = false)
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    override fun stop() {
        server.stop(1000, 5000)
    }


}
