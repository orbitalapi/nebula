package com.orbitalhq.nebula.events

import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.StackName
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentInfoWithState
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.StackStateEvent


fun computeStackStateEventFor(
    stackName: StackName,
    map: Map<InfrastructureComponent<*>, ComponentState>,
    components: List<InfrastructureComponent<*>>
): StackStateEvent {
    // group by the state
    val stateCounts = map.values.groupBy { it }
        // and count the number of entries in that state
        .mapValues { it.value.size }
    val componentInfos = components.map { stackComponent ->
        ComponentInfoWithState(
            stackComponent.name,
            stackComponent.type,
            stackComponent.id,
            stackComponent.currentState,
            stackComponent.componentInfo as ComponentInfo<*>?
        )
    }
    val stackState = mapOf(stackName to componentInfos)
    return StackStateEvent(stackName, stateCounts, stackState)
}