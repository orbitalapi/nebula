package com.orbitalhq.nebula.cli

import arrow.core.getOrElse
import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.NebulaStackWithSource
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.runtime.NebulaCompilationException
import com.orbitalhq.nebula.runtime.NebulaScriptExecutor
import com.orbitalhq.nebula.runtime.server.NebulaServer
import io.github.oshai.kotlinlogging.KotlinLogging
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
import kotlin.script.experimental.api.isError

@Command(
    name = "nebula",
    mixinStandardHelpOptions = true,
    version = ["Nebula 1.0"],
    description = ["Executes a Nebula script and manages services, or starts an HTTP server."]
)
class Nebula : Callable<Int> {

    companion object {
        private val logger = KotlinLogging.logger {}
    }

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

    private var fileWatcher: FileWatcher? = null
    private var currentStackRunner: StackRunner? = null

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

        // Initial script execution - don't exit if it fails, just log and wait
        loadAndStartScript(file, nebulaConfig)

        // Set up file watcher
        fileWatcher = FileWatcher(file) { changedFile ->
            logger.info { "Script file changed, reloading..." }
            reloadScript(changedFile, nebulaConfig)
        }
        fileWatcher?.start()

        Runtime.getRuntime().addShutdownHook(Thread {
            if (verbose) spec.commandLine().out.println("Shutting down services...")
            fileWatcher?.stop()
            currentStackRunner?.shutDownAll()
            if (verbose) spec.commandLine().out.println("Services shut down gracefully.")
        })

        spec.commandLine().out.println("Watching ${file.name} for changes - Press Ctrl+C to stop")
        while (true) {
            sleep(200)
        }
    }

    private fun loadAndStartScript(file: File, nebulaConfig: NebulaConfig) {
        val scriptExecutor = NebulaScriptExecutor(logCompilationErrors = false)
        val scriptContent = file.readText()

        val stackOrError = scriptExecutor.compileToStackWithSource(scriptContent, HostConfig.UNKNOWN)

        stackOrError.fold(
            ifLeft = { exception ->
                logCompilationErrors(exception)
                logger.warn { "Script has compilation errors. Waiting for changes..." }
            },
            ifRight = { stackWithSource ->
                val stackRunner = StackRunner(nebulaConfig)
                currentStackRunner = stackRunner
                stackRunner.submit(stackWithSource)
                spec.commandLine().out.println("${stackWithSource.stack.components.size} services running")
                logger.info { "Successfully loaded and started script" }
            }
        )
    }

    private fun reloadScript(file: File, nebulaConfig: NebulaConfig) {
        // Stop current stack
        currentStackRunner?.let { runner ->
            logger.info { "Stopping current stack..." }
            try {
                runner.shutDownAll()
            } catch (e: Exception) {
                logger.error(e) { "Error shutting down current stack" }
            }
            currentStackRunner = null
        }

        // Load and start new stack
        // Don't log errors inside the executor, log them from here.
        val scriptExecutor = NebulaScriptExecutor(logCompilationErrors = false)
        val scriptContent = file.readText()

        val stackOrError = scriptExecutor.compileToStackWithSource(scriptContent, HostConfig.UNKNOWN)

        stackOrError.fold(
            ifLeft = { exception ->
                logCompilationErrors(exception)
                logger.warn { "Script has compilation errors. Waiting for next change..." }
            },
            ifRight = { stackWithSource ->
                val stackRunner = StackRunner(nebulaConfig)
                currentStackRunner = stackRunner
                try {
                    stackRunner.submit(stackWithSource)
                    spec.commandLine().out.println("Reloaded: ${stackWithSource.stack.components.size} services running")
                    logger.info { "Successfully reloaded script" }
                } catch (e: Exception) {
                    logger.error(e) { "Error starting new stack" }
                    currentStackRunner = null
                }
            }
        )
    }

    private fun logCompilationErrors(exception: NebulaCompilationException) {
        logger.error { "Script compilation failed with ${exception.errors.size} error(s):" }
        exception.errors.forEach { diagnostic ->
            val location = diagnostic.location
            if (location != null) {
                logger.error { "  Line ${location.start.line}, Column ${location.start.col}: ${diagnostic.message}" }
            } else {
                logger.error { "  ${diagnostic.message}" }
            }
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