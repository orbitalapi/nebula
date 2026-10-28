package com.orbitalhq.nebula.runtime

import com.orbitalhq.nebula.check.NebulaStackChecker
import com.orbitalhq.nebula.check.Severity
import com.orbitalhq.nebula.check.SourcePosition
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.util.ServiceLoader

class NebulaScriptCheckerTest : DescribeSpec({

    val checker = NebulaScriptChecker()

    describe("NebulaScriptChecker") {
        it("accepts a script that compiles") {
            val result = checker.check(
                """
                stack {
                   http {
                      get("/hello") { call -> call.respondText("hello") }
                   }
                }
                """.trimIndent()
            )

            result.compiles shouldBe true
            result.errors.shouldBeEmpty()
        }

        it("reports a compilation error with its severity and position") {
            val result = checker.check(
                """
                stack {
                   htp {
                      get("/hello") { call -> call.respondText("hello") }
                   }
                }
                """.trimIndent()
            )

            result.compiles shouldBe false
            // A typo cascades (the block's contents no longer resolve either); the first error is the cause
            val error = result.errors.first()
            error.severity shouldBe Severity.ERROR
            error.message shouldContain "htp"
            error.location!!.start shouldBe SourcePosition(line = 2, column = 4)
            error.location!!.end shouldBe SourcePosition(line = 2, column = 7)
            error.describe() shouldBe "2:4: Unresolved reference 'htp'."
        }

        it("reports warnings without failing the check") {
            val result = checker.check(
                """
                @Deprecated("Use newPort()")
                fun oldPort() = 8080
                val port = oldPort()
                stack { }
                """.trimIndent()
            )

            result.compiles shouldBe true
            val warning = result.diagnostics.single()
            warning.severity shouldBe Severity.WARNING
            warning.message shouldContain "deprecated"
            warning.location!!.start.line shouldBe 3
        }

        it("rejects a script that doesn't end with a stack") {
            val result = checker.check(
                """
                val stack = stack { }
                println("done")
                """.trimIndent()
            )

            result.compiles shouldBe false
            result.errors.single().message shouldContain "where a Nebula stack was expected"
            result.errors.single().location shouldBe null
        }

        it("doesn't run the script") {
            val marker = Files.createTempFile("nebula-checker", ".marker")
            Files.delete(marker)

            checker.check(
                """
                java.io.File("${marker.toString().replace("\\", "\\\\")}").writeText("ran")
                stack { }
                """.trimIndent()
            ).compiles shouldBe true

            Files.exists(marker) shouldBe false
        }

        it("is registered with ServiceLoader") {
            ServiceLoader.load(NebulaStackChecker::class.java).single().shouldBeInstanceOf<NebulaScriptChecker>()
        }
    }
})
