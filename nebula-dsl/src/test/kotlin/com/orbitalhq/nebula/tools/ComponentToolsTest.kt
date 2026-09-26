package com.orbitalhq.nebula.tools

import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.NebulaStackWithSource
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.s3.s3
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.minutes

class ComponentToolsTest : DescribeSpec({
    lateinit var infra: StackRunner
    val http = HttpClient.newHttpClient()

    fun get(url: String): String = http.send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
    ).body()

    describe("component tools") {
        afterTest {
            infra.shutDownAll()
        }

        it("launches StackPort against an s3 component, and stops it with the component") {
            infra = stack {
                s3 {
                    bucket("tools-test-bucket") {
                        file("hello.txt", "Hello, world")
                    }
                }
            }.start()
            val stackName = infra.stacks.keys.single()
            val s3 = infra.s3.single()

            val offered = infra.tools(stackName)[s3.id].shouldNotBeNull().single()
            offered.id shouldBe "stackport"
            offered.state shouldBe ToolState.Available

            infra.launchTool(stackName, s3.id, "stackport").state shouldBe ToolState.Starting

            val running = eventually(3.minutes) {
                infra.tools(stackName)[s3.id]!!.single().also { it.state shouldBe ToolState.Running }
            }
            // StackPort reaches LocalStack over the Nebula network, using the component's credentials
            get("http://localhost:${running.hostPort}/api/s3/buckets") shouldContain "tools-test-bucket"

            // Launching again is a no-op
            infra.launchTool(stackName, s3.id, "stackport").hostPort shouldBe running.hostPort

            infra.stopComponent(stackName, s3.id)
            // Tools are only offered while the component runs, and the tool went down with it
            infra.tools(stackName)[s3.id] shouldBe null
            infra.startComponent(stackName, s3.id)
            infra.tools(stackName)[s3.id]!!.single().state shouldBe ToolState.Available
        }

        it("targets its own component when another stack has a component with the same name") {
            infra = StackRunner()
            // Both stacks get an `s3` component - so the network alias `s3` is ambiguous
            listOf("first" to "first-stack-bucket", "second" to "second-stack-bucket").forEach { (stackName, bucket) ->
                val stack = stack { s3 { bucket(bucket) {} } }
                infra.submit(NebulaStackWithSource(stack, "$stackName-source", HostConfig.UNKNOWN), stackName)
            }
            val s3Id = infra.s3.first().id
            infra.launchTool("second", s3Id, "stackport")
            val running = eventually(3.minutes) {
                infra.tools("second")[s3Id]!!.single().also { it.state shouldBe ToolState.Running }
            }

            val buckets = get("http://localhost:${running.hostPort}/api/s3/buckets")
            buckets shouldContain "second-stack-bucket"
            buckets shouldNotContain "first-stack-bucket"
        }

        it("refuses to launch a tool for a component that isn't running") {
            infra = stack {
                s3 { bucket("idle-bucket") {} }
            }.start()
            val stackName = infra.stacks.keys.single()
            val s3 = infra.s3.single()
            infra.stopComponent(stackName, s3.id)

            infra.tools(stackName).keys.shouldBeEmpty()
            shouldThrow<ToolNotAvailableException> {
                infra.launchTool(stackName, s3.id, "stackport")
            }
        }
    }
})
