package com.orbitalhq.nebula.check

/**
 * Checks that a Nebula stack script compiles, without running it.
 *
 * Implemented in nebula-runtime and registered with [java.util.ServiceLoader], so callers
 * can compile against this interface while keeping Nebula's runtime off their classpath -
 * Luna, for example, loads nebula-runtime in a class loader of its own, and shares only
 * this package with it. Everything in this package must therefore depend on nothing beyond
 * the JDK and the Kotlin stdlib.
 */
interface NebulaStackChecker {
    fun check(script: String): StackCheckResult
}

/**
 * The diagnostics from compiling a stack: warnings and errors, in the order the compiler
 * reported them.
 */
data class StackCheckResult(val diagnostics: List<StackDiagnostic>) {
    val errors: List<StackDiagnostic>
        get() = diagnostics.filter { it.severity.isError }

    val compiles: Boolean
        get() = errors.isEmpty()
}

data class StackDiagnostic(
    val severity: Severity,
    val message: String,
    /** Where in the script the problem is. Null for a problem with the script as a whole. */
    val location: SourceRange? = null,
) {
    /** `3:12: Unresolved reference 'htp'`, or just the message when there's no location. */
    fun describe(): String = location?.let { "${it.start.line}:${it.start.column}: $message" } ?: message
}

/** A span of the script. [end] is null when the compiler only reported where the problem starts. */
data class SourceRange(val start: SourcePosition, val end: SourcePosition? = null)

/** 1-based, as editors and the Kotlin compiler report them. */
data class SourcePosition(val line: Int, val column: Int)

/** Mirrors the Kotlin compiler's diagnostic levels. */
enum class Severity {
    DEBUG, INFO, WARNING, ERROR, FATAL;

    val isError: Boolean
        get() = this >= ERROR
}
