package com.orbitalhq.nebula.runtime

import com.orbitalhq.nebula.NebulaScript
import com.orbitalhq.nebula.NebulaStack
import java.io.File
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

class NebulaScriptExecutor {
    fun runScript(file: File): NebulaStack {
        return runScript(file.toScriptSource())
    }

    private fun runScript(source: SourceCode): NebulaStack {
        val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<NebulaScript>()
        val resultWithDiagnostics = BasicJvmScriptingHost().eval(source, compilationConfiguration, null)
        val evaluationResult = resultWithDiagnostics.valueOrThrow()
        return when (val returnValue = evaluationResult.returnValue) {
            is ResultValue.Value -> returnValue.value as NebulaStack
            else -> error("Unhandled branch: ${returnValue::class.simpleName}")
        }
    }

    fun toStack(string: String): NebulaStack {
        val source = string.toScriptSource()
        return runScript(source)
    }
}