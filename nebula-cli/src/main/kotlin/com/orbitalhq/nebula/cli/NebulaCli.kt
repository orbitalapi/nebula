package com.orbitalhq.nebula.cli

import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.runtime.NebulaScriptExecutor
import com.orbitalhq.nebula.runtime.server.NebulaServer
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.Network
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Parameters
import java.io.File
import java.lang.Thread.sleep
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

    @Option(names = ["--network"], description = ["The name of the docker network created"], defaultValue = "\${NEBULA_NETWORK:-nebula_network}")
    lateinit var networkName: String

    override fun call(): Int {
        val networkOrError = discoverActualNetworkName()
        if (networkOrError.isFailure) {
            return spec.exitCodeOnExecutionException()
        }
        val network = networkOrError.getOrThrow()
        spec.commandLine().out.println("Nebula using network name $networkName maps to ${network.id}")
        val nebulaConfig = NebulaConfig(networkName, network)
        when {
            scriptFile != null -> return executeScript(nebulaConfig)
            httpPort != null -> return startHttpServer(nebulaConfig)
            else -> throw ParameterException(
                spec.commandLine(),
                "Either a script file or --http option must be provided"
            )
        }
    }

    private fun discoverActualNetworkName():Result<Network> {
        val dockerClient = DockerClientFactory.lazyClient()
        val networks = dockerClient.listNetworksCmd().withNameFilter(networkName).exec()
        return when (networks.size) {
            0 -> {
                spec.commandLine().out.println("A network named $networkName does not exist - will create a temporary, isolated network")
                Result.success(Network.newNetwork())
            }
            1 -> {
                val network = networks.single()
                return Result.success(ExistingDockerNetwork(network.name))
            }
            else -> {
                spec.commandLine().err.println("Multiple networks were found matching the provided name $networkName - provide a more specific name, or remove the other networks")
                networks.forEach { spec.commandLine().err.println(it.name) }
                Result.failure(IllegalArgumentException("Multiple networks were found"))
            }
        }
    }

    private fun executeScript(nebulaConfig: NebulaConfig): Int {
        val file = scriptFile ?: throw ParameterException(spec.commandLine(), "Script file is required")
        if (!file.exists() || !file.isFile || !file.canRead()) {
            throw ParameterException(spec.commandLine(), "${file.toPath()} not found or cannot be read")
        }

        val scriptRunner = NebulaScriptExecutor()
        val stack = scriptRunner.runScript(file)

        val stackRunner = StackRunner(nebulaConfig)
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

    private fun startHttpServer(nebulaConfig: NebulaConfig): Int {
        // Placeholder function for HTTP server
        spec.commandLine().out.println("Starting HTTP server on port $httpPort")
        NebulaServer(httpPort!!, config = nebulaConfig).start()
        spec.commandLine().out.println("Server running - Press Ctrl+C to stop")
        while (true) {
            sleep(200)
        }
    }
}

fun main(args: Array<String>): Unit = System.exit(CommandLine(Nebula()).execute(*args))

class ExistingDockerNetwork(private val id: String) : Network {
    override fun close() {
    }

    override fun apply(base: Statement?, description: Description?): Statement? {
        return null
    }

    override fun getId(): String {
        return id
    }

}