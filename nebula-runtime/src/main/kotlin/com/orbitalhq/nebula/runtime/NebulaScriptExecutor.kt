package com.orbitalhq.nebula.runtime

import com.orbitalhq.nebula.NebulaScript
import com.orbitalhq.nebula.NebulaStack
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
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

    private fun runScript(source: SourceCode): NebulaStack {
        val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<NebulaScript>()
        val resultWithDiagnostics = BasicJvmScriptingHost().eval(source, compilationConfiguration, null)
        if (resultWithDiagnostics.isError()) {
            logError(resultWithDiagnostics)
        }
        val evaluationResult = resultWithDiagnostics.valueOrThrow()
        return when (val returnValue = evaluationResult.returnValue) {
            is ResultValue.Value -> returnValue.value as NebulaStack
            is ResultValue.Error -> {
                error(returnValue.error)
            }

            else -> {
                error("Unhandled branch: ${returnValue::class.simpleName}")
            }
        }
    }

    private fun logError(resultWithDiagnostics: ResultWithDiagnostics<EvaluationResult>) {
        logger.error { "The provided script has compilation errors" }
        resultWithDiagnostics.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }.forEach { logger.error { it.toString() } }
    }

    fun toStack(string: String): NebulaStack {
        val source = string.toScriptSource()
        return runScript(source)
    }
}