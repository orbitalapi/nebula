package com.orbitalhq.nebula.runtime

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.NebulaScript
import com.orbitalhq.nebula.NebulaStack
import com.orbitalhq.nebula.NebulaStackWithSource
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.isError
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.util.isError
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

class NebulaScriptExecutor {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    fun runScript(file: File): NebulaStack {
        return runScript(file.toScriptSource())
    }

    private fun compileStack(source: SourceCode): Either<NebulaCompilationException, NebulaStack> {
        val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<NebulaScript>()
        val resultWithDiagnostics = BasicJvmScriptingHost().eval(source, compilationConfiguration, null)
        if (resultWithDiagnostics.isError()) {
            logError(resultWithDiagnostics)
            return NebulaCompilationException(resultWithDiagnostics.reports).left()
        }
        val evaluationResult = resultWithDiagnostics.valueOrThrow()
        return when (val returnValue = evaluationResult.returnValue) {
            is ResultValue.Value -> {
                when (returnValue.value) {
                    is NebulaStack -> returnValue.value.right()
                    null -> NebulaCompilationException.forSyntheticDiagnostic("The script returned null, where a Nebula stack was expected. Ensure that the stack {} block is the last block in the script")
                        .left()

                    else -> NebulaCompilationException.forSyntheticDiagnostic("The script returned ${returnValue::class.simpleName}, where a Nebula stack was expected. Ensure that the stack {} block is the last block in the script")
                        .left()
                }
                (returnValue.value as NebulaStack).right()
            }

            is ResultValue.Error -> {
                NebulaCompilationException.forSyntheticDiagnostic("The script failed to evaluate - ${returnValue.error::class.simpleName} - ${returnValue.error.message}")
                    .left()
            }

            is ResultValue.Unit -> NebulaCompilationException.forSyntheticDiagnostic("The script returned ${returnValue::class.simpleName}, where a Nebula stack was expected. Ensure that the stack {} block is the last block in the script")
                .left()

            else -> {
                error("Unhandled branch: ${returnValue::class.simpleName}")
            }
        }
    }

    @Deprecated("Call compileStack instead", replaceWith = ReplaceWith("compileStack(source)"))
    private fun runScript(source: SourceCode): NebulaStack {
        return compileStack(source)
            .getOrElse {
                throw it as Throwable
            }
    }

    private fun logError(resultWithDiagnostics: ResultWithDiagnostics<EvaluationResult>) {
        logger.error { "The provided script has compilation errors" }
        resultWithDiagnostics.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
            .forEach { logger.error { it.toString() } }
    }

    @Deprecated("call toStackWithSource")
    fun toStack(string: String): NebulaStack {
        val source = string.toScriptSource()
        return runScript(source)
    }

    fun compileToStackWithSource(
        string: String,
        hostConfig: HostConfig
    ): Either<NebulaCompilationException, NebulaStackWithSource> {
        val source = string.toScriptSource()
        return compileStack(source)
            .map { NebulaStackWithSource(it, string, hostConfig) }
    }

    @Deprecated("call compileToStackWithSource")
    fun toStackWithSource(string: String, hostConfig: HostConfig): NebulaStackWithSource {
        val source = string.toScriptSource()
        val stack = runScript(source)
        return NebulaStackWithSource(stack, string, hostConfig)
    }
}

class NebulaCompilationException(val reports: List<ScriptDiagnostic>) : RuntimeException() {
    val errors = reports.filter { it.isError() }

    companion object {
        fun forSyntheticDiagnostic(message: String, severity: ScriptDiagnostic.Severity = ScriptDiagnostic.Severity.ERROR): NebulaCompilationException {
            return NebulaCompilationException(
                listOf(
                    ScriptDiagnostic(
                        -1,
                        message, severity,
                        location = SourceCode.Location(SourceCode.Position(1, 1), SourceCode.Position(1, 1))
                    )
                )
            )
        }
    }
}
