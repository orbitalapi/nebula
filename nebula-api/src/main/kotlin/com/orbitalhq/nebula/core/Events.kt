package com.orbitalhq.nebula.core

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.annotation.JsonDeserialize


typealias NebulaStackState = Map<StackName, List<ComponentInfoWithState>>

data class ComponentInfoWithState(
    val name: ComponentName,
    val type: ComponentType,
    val id: String,
    val state: ComponentLifecycleEvent,
    val componentInfo: ComponentInfo<*>?
) {
    /**
     * Returns an updated StackStateEvent where references to the container's host
     * are replaced with the provided host.
     *
     * This is intended for usecases where Nebula is on a different server from
     * the consumers. This was references to things like "localhost" get replaced
     * with the host that the consumer uses to reach nebula.
     */
    fun updateHostReferences(host: String): ComponentInfoWithState {
        return if (componentInfo != null) {
            copy(componentInfo = componentInfo.updateHostReferences(host))
        } else {
            this
        }
    }
}
typealias NebulaEnvVariablesMap = Map<StackName, Map<ComponentType, Map<EnvVarKey, EnvVarValue>>>
typealias StackName = String
typealias ComponentType = String
typealias ComponentName = String
typealias EnvVarKey = String
typealias EnvVarValue = String

/**
 * An optional interface for ContainerConfig classes,
 * which are exposing host details.
 *
 * Allows us to update host names to the host name known to consumers
 */
interface HostNameAwareContainerConfig<T> {
    fun updateHostReferences(containerHost: String, publicHost: String): T
}


data class ComponentInfo<T>(
    val container: ContainerInfo?,

    @JsonDeserialize(`as` = Map::class)
    val componentConfig: T,
    val type: ComponentType,
    val name: ComponentName,
    val id: String
) {
    /**
     * Returns an updated StackStateEvent where references to the container's host
     * are replaced with the provided host.
     *
     * This is intended for usecases where Nebula is on a different server from
     * the consumers. This was references to things like "localhost" get replaced
     * with the host that the consumer uses to reach nebula.
     */
    fun updateHostReferences(host: String): ComponentInfo<T> {
        return if (container != null && componentConfig is HostNameAwareContainerConfig<*>) {
            val containerHost = container.host
            copy(componentConfig = componentConfig.updateHostReferences(containerHost, host) as T)
        } else {
            this
        }
    }

    @get:JsonIgnore
    val componentConfigMap: Map<String, Any>
        get() {
            return componentConfig as Map<String, Any>
        }
}


data class ContainerInfo(
    val containerId: String,
    val imageName: String,
    val containerName: String,
    val host: String
)


data class StackStateEvent(
    val stackName: String,
    val stateCounts: Map<ComponentState, Int>,
    val stackState: NebulaStackState
) {
    /**
     * Returns an updated StackStateEvent where references to the container's host
     * are replaced with the provided host.
     *
     * This is intended for usecases where Nebula is on a different server from
     * the consumers. This was references to things like "localhost" get replaced
     * with the host that the consumer uses to reach nebula.
     */
    fun updateHostReferences(host: String): StackStateEvent {
        val updatedStackState = stackState.mapValues { (_, componentInfoList) ->
            componentInfoList.map { componentInfoWithState ->
                componentInfoWithState.updateHostReferences(host)
            }
        }
        return copy(stackState = updatedStackState)
    }
}


@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
sealed interface ComponentLifecycleEvent {
    val state: ComponentState
}

enum class ComponentState(
    /**
     * Indicates if this state indicates the server is transitions from one state to another.
     * It's a hint to display a "spinner" etc on the UI
     */
    val isTransitionState: Boolean
) {
    NotStarted(false),
    Starting(true),
    Running(false),
    Stopping(true),
    Stopped(false),
    Failed(false)
}

data object NotStartedEvent : ComponentLifecycleEvent {
    override val state: ComponentState = ComponentState.NotStarted
}

data class LifecycleUpdatedEvent(override val state: ComponentState) : ComponentLifecycleEvent

data class LifecycleEventWithMessage(override val state: ComponentState, val message: String) :
    ComponentLifecycleEvent