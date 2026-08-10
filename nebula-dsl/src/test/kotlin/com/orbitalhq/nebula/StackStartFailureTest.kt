package com.orbitalhq.nebula

import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.LifecycleUpdatedEvent
import com.orbitalhq.nebula.core.NotStartedEvent
import com.orbitalhq.nebula.logging.LogStream
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import reactor.core.publisher.Flux

/**
 * A component throwing out of start() must not kill the stack's start thread,
 * and must not prevent the stack's other components from starting.
 */
class StackStartFailureTest : DescribeSpec({

    describe("starting a stack where one component throws") {

        it("still starts the remaining components and does not propagate the exception") {
            val broken = ThrowingComponent("broken")
            val healthy = RecordingComponent("healthy")
            val stack = NebulaStack("test-stack", listOf(broken, healthy))

            val componentInfos = shouldNotThrowAny {
                stack.startComponents(NebulaConfig(), HostConfig.UNKNOWN)
            }

            healthy.started shouldBe true
            // Only the component that started successfully contributes state
            componentInfos.keys shouldBe setOf("recording")
        }
    }
})

private class ThrowingComponent(override val name: String) : InfrastructureComponent<Unit> {
    override val type = "throwing"
    override val componentInfo: ComponentInfo<Unit>? = null
    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = Flux.never()
    override val currentState: ComponentLifecycleEvent = NotStartedEvent
    override val logStream = LogStream(name)

    override fun start(nebulaConfig: NebulaConfig, hostConfig: HostConfig): ComponentInfo<Unit> {
        throw IllegalStateException("Deliberate start failure")
    }

    override fun stop() {}
}

private class RecordingComponent(override val name: String) : InfrastructureComponent<Unit> {
    override val type = "recording"
    var started = false
        private set

    override var componentInfo: ComponentInfo<Unit>? = null
        private set
    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = Flux.never()
    override val currentState: ComponentLifecycleEvent
        get() = if (started) LifecycleUpdatedEvent(ComponentState.Running) else NotStartedEvent
    override val logStream = LogStream(name)

    override fun start(nebulaConfig: NebulaConfig, hostConfig: HostConfig): ComponentInfo<Unit> {
        started = true
        componentInfo = ComponentInfo(container = null, componentConfig = Unit, type = type, name = name, id = id)
        return componentInfo!!
    }

    override fun stop() {}
}
