package com.orbitalhq.nebula.tools

import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.StackName
import com.orbitalhq.nebula.core.ComponentState
import io.github.oshai.kotlinlogging.KotlinLogging
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import reactor.core.Disposable
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

enum class ToolState { Available, Starting, Running, Failed }

/**
 * A tool offered by a component, and (if it has been launched) where to reach it.
 *
 * [hostPort] is the host-mapped port only - clients build the URL using whatever
 * host they reached Nebula on, so this works when Nebula is on another machine.
 */
data class ToolView(
    val id: String,
    val displayName: String,
    val description: String,
    val state: ToolState,
    val message: String? = null,
    val hostPort: Int? = null,
    val path: String
)

/** Thrown when a tool can't be launched because its component isn't running. */
class ToolNotAvailableException(message: String) : RuntimeException(message)

/**
 * Launches, tracks and stops the tools offered by [ProvidesTools] components.
 *
 * Tool instances live outside the stack's component list: they don't count toward
 * the stack's state, and aren't restarted with it. A tool is stopped when its
 * component stops (for whatever reason), or when its stack is shut down.
 */
class ToolRunner(private val network: Network) {
    private val logger = KotlinLogging.logger {}
    private val instances = ConcurrentHashMap<ToolKey, ToolInstance>()

    /** The tools offered by each running component in a stack, keyed by component id. */
    fun toolsFor(stackName: StackName, components: List<InfrastructureComponent<*>>): Map<String, List<ToolView>> {
        return components
            .filter { it is ProvidesTools && it.currentState.state == ComponentState.Running }
            .associate { component ->
                component.id to (component as ProvidesTools).tools().map { definition ->
                    instances[ToolKey(stackName, component.id, definition.id)]?.view()
                        ?: definition.availableView()
                }
            }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Launches a tool in the background, returning immediately with it Starting.
     * Launching a tool that's already starting or running returns the existing instance.
     */
    fun launch(stackName: StackName, component: InfrastructureComponent<*>, toolId: String): ToolView {
        if (component !is ProvidesTools || component.currentState.state != ComponentState.Running) {
            throw ToolNotAvailableException("Component ${component.id} must be running to launch its tools")
        }
        val definition = component.tools().find { it.id == toolId }
            ?: error("Component ${component.id} has no tool $toolId")
        val key = ToolKey(stackName, component.id, toolId)
        val instance = instances.compute(key) { _, existing ->
            if (existing != null && existing.state != ToolState.Failed) existing else ToolInstance(key, definition, component)
        }!!
        instance.startAsync()
        return instance.view()
    }

    fun stop(stackName: StackName, componentId: String, toolId: String): ToolView? {
        val instance = instances.remove(ToolKey(stackName, componentId, toolId)) ?: return null
        instance.stop()
        return instance.definition.availableView()
    }

    fun stopAll(stackName: StackName) {
        instances.keys.filter { it.stackName == stackName }.forEach { stop(it.stackName, it.componentId, it.toolId) }
    }

    private fun ToolDefinition.availableView() = ToolView(id, displayName, description, ToolState.Available, path = launch.path)

    private data class ToolKey(val stackName: StackName, val componentId: String, val toolId: String)

    private inner class ToolInstance(
        val key: ToolKey,
        val definition: ToolDefinition,
        val component: InfrastructureComponent<*>
    ) {
        private val spec = definition.launch
        private val container: GenericContainer<*> = GenericContainer(DockerImageName.parse(spec.image))
            .withNetwork(network)
            .withExposedPorts(spec.httpPort)
            .withEnv(spec.env)
            .waitingFor(
                Wait.forHttp(spec.readinessPath)
                    .forPort(spec.httpPort)
                    // Allows for pulling the image on first launch
                    .withStartupTimeout(Duration.ofMinutes(3))
            )

        @Volatile
        var state = ToolState.Starting
            private set

        @Volatile
        private var message: String? = null

        @Volatile
        private var hostPort: Int? = null
        private var started = false

        @Volatile
        private var stopped = false
        private var componentSubscription: Disposable? = null

        @Synchronized
        fun startAsync() {
            if (started) return
            started = true
            // The tool is only useful while its component is up - stop it when the component goes away.
            componentSubscription = component.lifecycleEvents
                .filter { it.state != ComponentState.Running }
                .subscribe {
                    logger.info { "Component ${key.componentId} is ${it.state}; stopping tool ${key.toolId}" }
                    stop(key.stackName, key.componentId, key.toolId)
                }
            // Tool logs show up in the stack's logs, alongside the component's.
            component.logStream.attachToUnstartedContainer(container, "${component.name}/${definition.id}")
            thread(name = "tool-${key.componentId}-${key.toolId}") {
                try {
                    logger.info { "Launching tool ${key.toolId} (${spec.image}) for ${key.componentId} in stack ${key.stackName}" }
                    container.start()
                    if (stopped) {
                        // Stopped while we were starting - don't leave the container behind.
                        stopContainer()
                        return@thread
                    }
                    hostPort = container.getMappedPort(spec.httpPort)
                    state = ToolState.Running
                } catch (e: Exception) {
                    logger.error(e) { "Tool ${key.toolId} for ${key.componentId} failed to start" }
                    message = e.message ?: e::class.simpleName
                    state = ToolState.Failed
                    stop()
                }
            }
        }

        fun stop() {
            stopped = true
            componentSubscription?.dispose()
            stopContainer()
        }

        private fun stopContainer() {
            try {
                container.stop()
            } catch (e: Exception) {
                logger.warn(e) { "Error stopping tool ${key.toolId} for ${key.componentId}" }
            }
        }

        fun view() = ToolView(
            id = definition.id,
            displayName = definition.displayName,
            description = definition.description,
            state = state,
            message = message,
            hostPort = hostPort,
            path = spec.path
        )
    }
}
