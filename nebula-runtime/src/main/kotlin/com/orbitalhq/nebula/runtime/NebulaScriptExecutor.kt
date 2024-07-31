package com.orbitalhq.nebula.runtime

import com.orbitalhq.nebula.NebulaScript
import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.valueOrThrow
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

class NebulaScriptExecutor {
    fun runScript(file: File): EvaluationResult {
        val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<NebulaScript>()
        return BasicJvmScriptingHost().eval(file.toScriptSource(), compilationConfiguration, null)
            .valueOrThrow()

    }
}