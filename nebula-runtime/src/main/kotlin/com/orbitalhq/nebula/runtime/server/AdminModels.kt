package com.orbitalhq.nebula.runtime.server

import com.orbitalhq.nebula.StackName
import com.orbitalhq.nebula.core.StackStateEvent
import kotlin.script.experimental.api.ScriptDiagnostic

/** A single compilation diagnostic, flattened for the admin API. */
data class CompilationErrorDto(
    val message: String,
    val line: Int?,
    val column: Int?,
    val severity: String
)

/**
 * A stack that was submitted but failed to compile. Held in memory until a
 * valid version is submitted under the same name (which replaces it).
 */
data class FailedSubmission(
    val name: StackName,
    val source: String,
    val compilationErrors: List<CompilationErrorDto>
)

/**
 * Unified admin view of a submitted stack: either a compiled stack (with its
 * live component state and source) or a failed submission (no state, but with
 * its source and the compilation errors). `compilationErrors` is empty for a
 * stack that compiled successfully.
 */
data class AdminStackView(
    val name: StackName,
    val stackState: StackStateEvent?,
    val source: String,
    val compilationErrors: List<CompilationErrorDto>
) {
    val failedCompilation: Boolean get() = compilationErrors.isNotEmpty()
}

fun ScriptDiagnostic.toCompilationErrorDto(): CompilationErrorDto = CompilationErrorDto(
    message = message,
    line = location?.start?.line,
    column = location?.start?.col,
    severity = severity.name
)
