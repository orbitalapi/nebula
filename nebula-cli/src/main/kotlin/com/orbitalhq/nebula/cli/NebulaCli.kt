package com.orbitalhq.nebula.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.file
import com.orbitalhq.nebula.ServicesSpec
import com.orbitalhq.nebula.start
import com.orbitalhq.nebula.runtime.NebulaScriptExecutor
import java.lang.Thread.sleep
import kotlin.script.experimental.api.ResultValue
import kotlin.system.exitProcess

class Nebula : CliktCommand() {
    private val scriptFile by argument(help = "The script file to execute").file(
        mustExist = true,
        canBeFile = true,
        mustBeReadable = true
    )

    override fun run() {
        val scriptRunner = NebulaScriptExecutor()
        val result = scriptRunner.runScript(scriptFile)
        val services = when (val returnValue = result.returnValue) {
            is ResultValue.Value -> returnValue.value as ServicesSpec
            else -> {
                echo("The script failed: ${returnValue::class.simpleName}", err = true)
                exitProcess(1)
            }
        }
        val infraExecutor = services.start()
        // Setup a shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            println("Shutting down services...")
            infraExecutor.shutDown()
            println("Services shut down gracefully.")
        })

        echo("${services.components.size} services running - Press Ctrl+C to stop")
        while (true) {
            sleep(200)
        }
    }

}

fun main(args: Array<String>) = Nebula().main(args)