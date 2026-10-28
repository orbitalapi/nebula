package com.orbitalhq.nebula.runtime

import com.orbitalhq.nebula.NebulaScript
import com.orbitalhq.nebula.NebulaStack
import com.orbitalhq.nebula.check.NebulaStackChecker
import com.orbitalhq.nebula.check.Severity
import com.orbitalhq.nebula.check.SourcePosition
import com.orbitalhq.nebula.check.SourceRange
import com.orbitalhq.nebula.check.StackCheckResult
import com.orbitalhq.nebula.check.StackDiagnostic
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

/**
 * Checks that a stack script compiles, without running it.
 *
 * Unlike [NebulaScriptExecutor], nothing in the script is evaluated - not even the
 * `stack { }` block - so it's safe to call on a script nobody has reviewed, such as
 * one an agent has just written. The trade-off is that mistakes which only surface
 * when the block runs (a resource file that isn't there) aren't caught.
 *
 * Registered with [java.util.ServiceLoader] as a [NebulaStackChecker].
 */
class NebulaScriptChecker : NebulaStackChecker {

    /**
     * Compiles [script], and returns its warnings and errors. The compiler's debug and
     * info diagnostics are left out.
     */
    override fun check(script: String): StackCheckResult {
        val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<NebulaScript>()
        val result = runBlocking {
            JvmScriptCompiler()(script.toScriptSource(), compilationConfiguration)
        }
        val diagnostics = result.reports
            .filter { it.severity >= ScriptDiagnostic.Severity.WARNING }
            .map { it.toStackDiagnostic() }
        if (result !is ResultWithDiagnostics.Success) {
            return StackCheckResult(
                diagnostics.ifEmpty { listOf(StackDiagnostic(Severity.ERROR, "The script could not be compiled")) }
            )
        }

        // The executor checks this by evaluating the script; the compiled result's
        // declared type tells us the same thing without running anything
        val resultType = result.value.resultField?.second?.typeName
        return if (resultType == NebulaStack::class.qualifiedName) {
            StackCheckResult(diagnostics)
        } else {
            StackCheckResult(
                diagnostics + StackDiagnostic(
                    Severity.ERROR,
                    "The script returns ${resultType ?: "nothing"}, where a Nebula stack was expected. Ensure that the stack {} block is the last block in the script"
                )
            )
        }
    }

    private fun ScriptDiagnostic.toStackDiagnostic() = StackDiagnostic(
        severity = Severity.valueOf(severity.name),
        message = message,
        location = location?.let { SourceRange(it.start.toSourcePosition(), it.end?.toSourcePosition()) }
    )

    private fun SourceCode.Position.toSourcePosition() = SourcePosition(line, col)
}
