package com.orbitalhq.nebula.runtime

import arrow.core.Either
import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.NebulaStackWithSource
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.core.ComponentState
import com.orbitalhq.nebula.core.ResourceContent
import com.orbitalhq.nebula.core.StackBundle
import com.orbitalhq.nebula.http.http
import com.orbitalhq.nebula.resources.StackBundleUnpacker
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The end-to-end path a bundle takes: submitted as JSON, unpacked into a
 * directory of its own, bound while the script is compiled, and read by the
 * running stack.
 *
 * This is the test that would fail if the bundle were unpacked but never reached
 * the script - the compile step would still succeed, and a test that only checked
 * "the stack compiles" would pass while nothing had actually been shipped.
 */
class StackBundleExecutionTest : DescribeSpec({

    val salesCsv = "Title,Tickets\nA New Hope,249\n"

    fun executorWithTempBundles(): Pair<NebulaScriptExecutor, Path> {
        val base = Files.createTempDirectory("bundle-execution-test").toRealPath()
        return NebulaScriptExecutor(bundleUnpacker = StackBundleUnpacker(base)) to base
    }

    fun Either<NebulaCompilationException, NebulaStackWithSource>.compiled(): NebulaStackWithSource =
        fold({ error("Expected the bundle to compile, but it failed: ${it.errors.joinToString { e -> e.message }}") }, { it })

    fun Either<NebulaCompilationException, NebulaStackWithSource>.rejection(): String =
        fold({ it.errors.joinToString { error -> error.message } }, { error("Expected the submission to be rejected") })

    suspend fun HttpClient.body(url: String): String = get(url).bodyAsText()

    describe("a stack submitted as a bundle") {

        it("serves a file that shipped with the script") {
            val (executor, _) = executorWithTempBundles()
            val bundle = StackBundle(
                script = """
                    stack {
                       val sales = resources.readText("data/sales.csv")
                       val shipped = resources.names.joinToString(",")
                       http {
                          get("/sales") { call -> call.respondText(sales) }
                          get("/shipped") { call -> call.respondText(shipped) }
                       }
                    }
                """.trimIndent(),
                resources = mapOf(
                    "data/sales.csv" to ResourceContent.text(salesCsv),
                    "specs/orders.yaml" to ResourceContent.text("openapi: 3.0.0")
                )
            )

            val compiled = executor.compileBundle("films", bundle, HostConfig.UNKNOWN).compiled()

            val runner = StackRunner()
            try {
                runner.submit(compiled)
                val component = runner.http.single()
                component.currentState.state shouldBe ComponentState.Running

                runBlocking {
                    val client = HttpClient()
                    // The content came from the submission, not from any file on the
                    // machine running Nebula.
                    client.body("${component.baseUrl}/sales") shouldBe salesCsv
                    client.body("${component.baseUrl}/shipped") shouldBe "data/sales.csv,specs/orders.yaml"
                    client.close()
                }
            } finally {
                runner.shutDownAll()
            }
        }

        it("resolves a relative path given to a provider through the bundle") {
            val (executor, _) = executorWithTempBundles()
            // Providers that read files (s3's file(path), apiGateway's restApiFromFile)
            // all go through resolveFilePath. A relative path must land inside the
            // bundle - that is what lets a project ship its own CSV or OpenAPI spec.
            val bundle = StackBundle(
                script = """
                    stack {
                       val resolved = resources.resolveFilePath("data/sales.csv")
                       http {
                          get("/resolved") { call -> call.respondText(resolved) }
                       }
                    }
                """.trimIndent(),
                resources = mapOf("data/sales.csv" to ResourceContent.text(salesCsv))
            )

            val compiled = executor.compileBundle("films", bundle, HostConfig.UNKNOWN).compiled()
            val bundleRoot = compiled.resources.root ?: error("No bundle directory was created")

            val runner = StackRunner()
            try {
                runner.submit(compiled)
                runBlocking {
                    val client = HttpClient()
                    val resolved = client.body("${runner.http.single().baseUrl}/resolved")
                    resolved shouldBe bundleRoot.resolve("data/sales.csv").toString()
                    Files.readString(Path.of(resolved)) shouldBe salesCsv
                    client.close()
                }
            } finally {
                runner.shutDownAll()
            }
        }

        it("fails the submission when a script reads outside its bundle") {
            val (executor, _) = executorWithTempBundles()
            val bundle = StackBundle(
                script = """
                    stack {
                       val stolen = resources.readText("../../../etc/passwd")
                       http { get("/x") { call -> call.respondText(stolen) } }
                    }
                """.trimIndent(),
                resources = mapOf("data/sales.csv" to ResourceContent.text(salesCsv))
            )

            executor.compileBundle("films", bundle, HostConfig.UNKNOWN)
                .rejection().shouldContain("'..' segment")
        }

        it("rejects a bundle whose resources would escape, before compiling anything") {
            val (executor, base) = executorWithTempBundles()
            val bundle = StackBundle(
                script = "stack {}",
                resources = mapOf("../escaped.txt" to ResourceContent.text("pwned"))
            )

            executor.compileBundle("films", bundle, HostConfig.UNKNOWN)
                .rejection().shouldContain("'..' segment")

            base.parent.resolve("escaped.txt").exists() shouldBe false
        }

        it("cleans up the unpacked bundle when the script fails to compile") {
            val (executor, base) = executorWithTempBundles()
            val bundle = StackBundle(
                script = "this is not a nebula script",
                resources = mapOf("data/sales.csv" to ResourceContent.text(salesCsv))
            )

            executor.compileBundle("films", bundle, HostConfig.UNKNOWN).rejection()

            // A rejected submission leaves nothing behind.
            Files.list(base).use { it.count() } shouldBe 0L
        }
    }

    describe("a stack submitted without resources") {
        it("behaves exactly as a script-only submission does") {
            val (executor, base) = executorWithTempBundles()
            val compiled = executor
                .compileBundle("films", StackBundle.scriptOnly("stack {}"), HostConfig.UNKNOWN)
                .compiled()

            compiled.resources.hasBundle shouldBe false
            compiled.submissionKey shouldBe "stack {}\u0000"
            Files.list(base).use { it.count() } shouldBe 0L
        }

        it("leaves a plain script submission with no bundle bound") {
            val (executor, _) = executorWithTempBundles()
            executor.compileToStackWithSource("stack {}", HostConfig.UNKNOWN)
                .compiled().resources.hasBundle shouldBe false
        }
    }

    describe("resubmission") {
        it("replaces a running stack when only its resources changed") {
            val (executor, _) = executorWithTempBundles()
            val script = """
                stack {
                   val sales = resources.readText("data/sales.csv")
                   http { get("/sales") { call -> call.respondText(sales) } }
                }
            """.trimIndent()

            fun bundleWith(content: String) = executor.compileBundle(
                "films",
                StackBundle(script, mapOf("data/sales.csv" to ResourceContent.text(content))),
                HostConfig.UNKNOWN
            ).compiled().withName("films")

            val runner = StackRunner()
            try {
                runner.submit(bundleWith("v1"))
                runBlocking {
                    val client = HttpClient()
                    client.body("${runner.http.single().baseUrl}/sales") shouldBe "v1"
                    client.close()
                }

                // Same script, different CSV. Deduping on the script alone would treat
                // this as a duplicate submission and keep serving the old content.
                runner.submit(bundleWith("v2"))
                runBlocking {
                    val client = HttpClient()
                    client.body("${runner.http.single().baseUrl}/sales") shouldBe "v2"
                    client.close()
                }
            } finally {
                runner.shutDownAll()
            }
        }

        it("deletes the replaced stack's unpacked bundle") {
            val (executor, _) = executorWithTempBundles()
            fun bundleWith(content: String) = executor.compileBundle(
                "films",
                StackBundle("stack {}", mapOf("data/sales.csv" to ResourceContent.text(content))),
                HostConfig.UNKNOWN
            ).compiled().withName("films")

            val runner = StackRunner()
            try {
                val first = bundleWith("v1")
                runner.submit(first)
                val firstRoot = first.resources.root ?: error("No bundle directory was created")

                runner.submit(bundleWith("v2"))

                firstRoot.exists() shouldBe false
            } finally {
                runner.shutDownAll()
            }
        }

        it("removes the unpacked bundle when the stack is removed") {
            val (executor, _) = executorWithTempBundles()
            val compiled = executor.compileBundle(
                "films",
                StackBundle("stack {}", mapOf("data/sales.csv" to ResourceContent.text(salesCsv))),
                HostConfig.UNKNOWN
            ).compiled().withName("films")

            val runner = StackRunner()
            runner.submit(compiled)
            val root = compiled.resources.root ?: error("No bundle directory was created")

            runner.removeStack("films")

            root.exists() shouldBe false
        }
    }
})
