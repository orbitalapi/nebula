package com.orbitalhq.nebula

import com.orbitalhq.nebula.http.HttpDsl
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

@KotlinScript(
    fileExtension = "nebula.kts",
    compilationConfiguration = NebulaCompilationConfiguration::class,
    displayName = "Nebula",
)
abstract class NebulaScript

val systemDefaultImports = setOf(
    "com.orbitalhq.nebula.stack",
    "kotlin.random.Random"
)

val allDefaultImports = listOf(
    systemDefaultImports,
    HttpDsl.defaultImports
).flatten()

object NebulaCompilationConfiguration : ScriptCompilationConfiguration({
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    defaultImports(allDefaultImports)

})