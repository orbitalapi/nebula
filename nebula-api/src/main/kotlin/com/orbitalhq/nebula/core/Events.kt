package com.orbitalhq.nebula.core

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize


typealias NebulaStackState = Map<StackName, List<ComponentInfoWithState>>

data class ComponentInfoWithState(
    val name: ComponentName,
    val type: ComponentType,
    val id: String,
    val state: ComponentLifecycleEvent,
    val componentInfo: ComponentInfo<*>?
)
typealias NebulaEnvVariablesMap = Map<StackName, Map<ComponentType, Map<EnvVarKey, EnvVarValue>>>
typealias StackName = String
typealias ComponentType = String
typealias ComponentName = String
typealias EnvVarKey = String
typealias EnvVarValue = String

data class ComponentInfo<T>(
    val container: ContainerInfo?,

    @JsonDeserialize(`as` = Map::class)
    val componentConfig: T,
    val type: ComponentType,
    val name: ComponentName,
    val id: String
) {
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
)


data class StackStateEvent(
    val stackName: String,
    val stateCounts: Map<ComponentState, Int>,
    val stackState: NebulaStackState
)


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