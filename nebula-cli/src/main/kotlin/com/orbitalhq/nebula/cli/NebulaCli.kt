package com.orbitalhq.nebula.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import com.orbitalhq.nebula.NebulaStack
import com.orbitalhq.nebula.start
import com.orbitalhq.nebula.runtime.NebulaScriptExecutor
import picocli.CommandLine.ParameterException
import java.io.File
import java.lang.Thread.sleep
import kotlin.script.experimental.api.ResultValue
import java.util.concurrent.Callable

@Command(
    name = "nebula",
    mixinStandardHelpOptions = true,
    version = ["Nebula 1.0"],
    description = ["Executes a Nebula script and manages services."]
)
class Nebula : Callable<Int> {

    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    @Parameters(index = "0", description = ["The script file to execute"])
    private lateinit var scriptFile: File

    @Option(names = ["-v", "--verbose"], description = ["Enable verbose output"])
    private var verbose: Boolean = false


    override fun call(): Int {
        if (!scriptFile.exists() || !scriptFile.isFile || !scriptFile.canRead()) {
            throw ParameterException(spec.commandLine(),  "${scriptFile.toPath()} not found or cannot be read")
        }

        val scriptRunner = NebulaScriptExecutor()
        val result = scriptRunner.runScript(scriptFile)
        val services = when (val returnValue = result.returnValue) {
            is ResultValue.Value -> returnValue.value as NebulaStack
            else -> {
                spec.commandLine().err.println("The script failed: ${returnValue::class.simpleName}")
                return 1
            }
        }
        val infraExecutor = services.start()

        Runtime.getRuntime().addShutdownHook(Thread {
            if (verbose) spec.commandLine().out.println("Shutting down services...")
            infraExecutor.shutDownAll()
            if (verbose) spec.commandLine().out.println("Services shut down gracefully.")
        })

        spec.commandLine().out.println("${services.components.size} services running - Press Ctrl+C to stop")
        while (true) {
            sleep(200)
        }
    }
}

fun main(args: Array<String>): Unit = System.exit(CommandLine(Nebula()).execute(*args))