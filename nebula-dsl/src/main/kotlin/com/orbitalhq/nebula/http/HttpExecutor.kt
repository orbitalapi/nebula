package com.orbitalhq.nebula.http

import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.containerInfoFrom
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.core.ComponentName
import com.orbitalhq.nebula.events.ComponentLifecycleEventSource
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import reactor.core.publisher.Flux
import java.net.ServerSocket

val StackRunner.http: List<HttpExecutor>
    get() {
        return this.component<HttpExecutor>()
    }

class HttpExecutor(private val config: HttpConfig) : InfrastructureComponent<HttpServerConfig> {
    override val type = "http"
    override val name = config.name

    private val port = if (config.port == 0) {
        findFreePort()
    } else {
        config.port
    }

    private val eventSource = ComponentLifecycleEventSource()

    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = eventSource.events
    override val currentState: ComponentLifecycleEvent
        get() {
            return eventSource.currentState
        }

    val baseUrl: String
        get() {
            return "http://localhost:$port"
        }
    lateinit var server: NettyApplicationEngine
        private set

    override var componentInfo: ComponentInfo<HttpServerConfig>? = null
        private set

    override fun start():ComponentInfo<HttpServerConfig> {
        eventSource.starting()
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
        componentInfo =  ComponentInfo(
            container = null,
            componentConfig = HttpServerConfig(port),
            type = type,
            name = name,
            id = id
        )
        eventSource.running()
        return componentInfo!!
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    override fun stop() {
        eventSource.stopping()
        server.stop(1000, 5000)
        eventSource.stopped()
    }


}


data class HttpServerConfig(
    val port: Int
)