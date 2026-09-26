package com.orbitalhq.nebula.tools

/**
 * Implemented by components that can launch extra tooling alongside themselves -
 * typically a browser / admin console for poking around in the thing that's running
 * (eg: StackPort for LocalStack, kafka-ui for Kafka).
 *
 * The component only *describes* its tools, since it's the one that knows the
 * coordinates and credentials a tool needs. Launching, networking and cleanup are
 * handled generically by [ToolRunner], so no tool gets special treatment.
 *
 * Tools are launched on demand (from the admin UI), never as part of starting a stack.
 */
interface ProvidesTools {
    /**
     * The tools this component offers. Only called while the component is running,
     * so implementations may read state that's only available once started
     * (eg: generated credentials).
     */
    fun tools(): List<ToolDefinition>
}

data class ToolDefinition(
    /** Unique within the component. Used in URLs, eg `stackport`. */
    val id: String,
    val displayName: String,
    val description: String,
    val launch: ContainerToolSpec
)

/**
 * A tool that runs as a container on the Nebula network, alongside the component it serves.
 * Reach the component via [com.orbitalhq.nebula.uniqueNetworkHost] and its *internal* port - the
 * tool is always inside the docker network, regardless of [com.orbitalhq.nebula.ConsumerConnectivity].
 */
data class ContainerToolSpec(
    val image: String,
    val env: Map<String, String> = emptyMap(),
    /** The port the tool serves its UI on, inside the container. Mapped to a random host port. */
    val httpPort: Int,
    /** Where to open the tool's UI. */
    val path: String = "/",
    /** An HTTP path that returns 2xx once the tool is ready. Defaults to [path]. */
    val readinessPath: String = path
)
