package com.orbitalhq.nebula

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

object NebulaCompilationConfiguration : ScriptCompilationConfiguration({
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    defaultImports(
        "com.orbitalhq.nebula.services",
        "kotlin.random.Random"
    )
})