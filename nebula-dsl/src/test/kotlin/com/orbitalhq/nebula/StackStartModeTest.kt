package com.orbitalhq.nebula

import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.http.http
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.ktor.server.response.*
import kotlin.time.Duration.Companion.seconds

class StackStartModeTest : DescribeSpec({

    fun httpStack(response: String) = NebulaStackWithSource(
        stack {
            http {
                get("/hello") { call ->
                    call.respondText(response)
                }
            }
        },
        source = "script-$response",
        hostConfig = HostConfig.UNKNOWN
    ).withName("start-mode-test")

    fun stackStateIn(runner: StackRunner, name: StackName): Map<ComponentState, Int> =
        runner.stackEvents(name).blockFirst()!!.stateCounts

    describe("StackStartMode.MANUAL") {
        it("holds a submitted stack until it's started") {
            val runner = StackRunner(NebulaConfig(stackStart = StackStartMode.MANUAL))
            try {
                runner.submit(httpStack("v1"))

                runner.http.single().currentState.state shouldBe ComponentState.NotStarted
                runner.stateState.shouldBeEmpty()
                // Consumers listening to the stack's events see it waiting
                stackStateIn(runner, "start-mode-test") shouldBe mapOf(ComponentState.NotStarted to 1)

                runner.startStack("start-mode-test")

                runner.http.single().currentState.state shouldBe ComponentState.Running
                runner.stateState["start-mode-test"]!!.shouldContainKey("http")
                // ...and hear about it once it's started
                eventually(5.seconds) {
                    stackStateIn(runner, "start-mode-test") shouldBe mapOf(ComponentState.Running to 1)
                }
            } finally {
                runner.shutDownAll()
            }
        }

        it("holds a replacement stack too") {
            val runner = StackRunner(NebulaConfig(stackStart = StackStartMode.MANUAL))
            try {
                runner.submit(httpStack("v1"))
                runner.startStack("start-mode-test")
                runner.http.single().currentState.state shouldBe ComponentState.Running

                runner.submit(httpStack("v2"))

                runner.http.single().currentState.state shouldBe ComponentState.NotStarted
            } finally {
                runner.shutDownAll()
            }
        }

        it("leaves a started stack running when the same source is resubmitted") {
            val runner = StackRunner(NebulaConfig(stackStart = StackStartMode.MANUAL))
            try {
                runner.submit(httpStack("v1"))
                runner.startStack("start-mode-test")

                runner.submit(httpStack("v1"))

                runner.http.single().currentState.state shouldBe ComponentState.Running
            } finally {
                runner.shutDownAll()
            }
        }
    }

    describe("StackStartMode.AUTO") {
        it("starts a stack as soon as it's submitted") {
            val runner = StackRunner(NebulaConfig(stackStart = StackStartMode.AUTO))
            try {
                runner.submit(httpStack("v1"))
                runner.http.single().currentState.state shouldBe ComponentState.Running
            } finally {
                runner.shutDownAll()
            }
        }
    }
})
