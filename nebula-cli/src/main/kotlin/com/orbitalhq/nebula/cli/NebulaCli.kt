package com.orbitalhq.nebula.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import com.orbitalhq.nebula.NebulaStack
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.runtime.NebulaScriptExecutor
import com.orbitalhq.nebula.runtime.server.NebulaServer
import picocli.CommandLine.ParameterException
import java.io.File
import java.lang.Thread.sleep
import kotlin.script.experimental.api.ResultValue
import java.util.concurrent.Callable

@Command(
    name = "nebula",
    mixinStandardHelpOptions = true,
    version = ["Nebula 1.0"],
    description = ["Executes a Nebula script and manages services, or starts an HTTP server."]
)
class Nebula : Callable<Int> {

    @CommandLine.Spec
    private lateinit var spec: CommandLine.Model.CommandSpec

    @Parameters(index = "0", description = ["The script file to execute"], arity = "0..1")
    private var scriptFile: File? = null

    @Option(names = ["--http"], description = ["Start HTTP server on specified port"], arity = "0..1")
    private var httpPort: Int? = null

    @Option(names = ["-v", "--verbose"], description = ["Enable verbose output"])
    private var verbose: Boolean = false

    override fun call(): Int {
        when {
            scriptFile != null -> return executeScript()
            httpPort != null -> return startHttpServer()
            else -> throw ParameterException(
                spec.commandLine(),
                "Either a script file or --http option must be provided"
            )
        }
    }

    private fun executeScript(): Int {
        val file = scriptFile ?: throw ParameterException(spec.commandLine(), "Script file is required")
        if (!file.exists() || !file.isFile || !file.canRead()) {
            throw ParameterException(spec.commandLine(), "${file.toPath()} not found or cannot be read")
        }

        val scriptRunner = NebulaScriptExecutor()
        val stack = scriptRunner.runScript(file)
        val stackRunner = StackRunner()
        stackRunner.submit(stack)
        Runtime.getRuntime().addShutdownHook(Thread {
            if (verbose) spec.commandLine().out.println("Shutting down services...")
            stackRunner.shutDownAll()
            if (verbose) spec.commandLine().out.println("Services shut down gracefully.")
        })

        spec.commandLine().out.println("${stack.components.size} services running - Press Ctrl+C to stop")
        while (true) {
            sleep(200)
        }
    }

    private fun startHttpServer(): Int {
        // Placeholder function for HTTP server
        spec.commandLine().out.println("Starting HTTP server on port $httpPort")
        NebulaServer(httpPort!!).start()
        spec.commandLine().out.println("Server running - Press Ctrl+C to stop")
        while (true) {
            sleep(200)
        }
    }
}

fun main(args: Array<String>): Unit = System.exit(CommandLine(Nebula()).execute(*args))