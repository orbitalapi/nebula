package com.orbitalhq.nebula.cli

import arrow.core.getOrElse
import com.orbitalhq.nebula.ConsumerConnectivity
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
import kotlin.system.exitProcess

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

    @Option(
        names = ["--connectivity"],
        description = [
            "How consumers reach the started containers. ",
            "host (default): emit localhost + host-mapped ports (developer CLI / Linux host-networking). ",
            "network: emit the container's network alias + internal port, for consumers on the same docker network (eg. Orbital in docker-compose)."
        ],
        defaultValue = "\${NEBULA_CONNECTIVITY:-host}"
    )
    lateinit var connectivity: ConsumerConnectivity

    private var fileWatcher: FileWatcher? = null
    private var currentStackRunner: StackRunner? = null

    override fun call(): Int {
        spec.commandLine().out.println("Nebula emitting container coordinates for $connectivity connectivity")
        val networkOrError = resolveNetwork()
        if (networkOrError.isFailure) {
            return spec.exitCodeOnExecutionException()
        }
        val network = networkOrError.getOrThrow()
        val nebulaConfig = NebulaConfig(networkName, network, connectivity)
        when {
            scriptFile != null -> return executeScript(nebulaConfig)
            httpPort != null -> return startHttpServer(nebulaConfig)
            else -> throw ParameterException(
                spec.commandLine(),
                "Either a script file or --http option must be provided"
            )
        }
    }

    /**
     * Resolves the docker network to attach child containers to.
     *
     * In `network` connectivity mode Nebula runs inside a container alongside
     * its consumers (eg. Orbital in docker-compose), so we attach children to
     * *our own* network - this is the only reliable way to pick the right one
     * when several `*_$networkName` networks exist on the host (eg. multiple
     * compose projects).
     *
     * In `host` mode consumers reach containers via host port-mapping, so the
     * network identity is irrelevant - we always create an isolated network
     * (enough for intra-stack DNS aliases to resolve) and avoid any docker
     * network discovery that could fail.
     */
    private fun resolveNetwork(): Result<Network> = when (connectivity) {
        ConsumerConnectivity.NETWORK -> resolveOwnContainerNetwork()
        ConsumerConnectivity.HOST -> {
            spec.commandLine().out.println(
                "Nebula running in host connectivity mode - started containers will be placed on an isolated docker network and reached via mapped ports"
            )
            Result.success(Network.newNetwork())
        }
    }

    /**
     * Determines the network by inspecting the container Nebula itself is
     * running in, and selecting the attached network whose name matches
     * [networkName].
     */
    private fun resolveOwnContainerNetwork(): Result<Network> {
        if (!isRunningInContainer()) {
            return fail(
                "Running with --connectivity=network, but Nebula does not appear to be running inside a container. " +
                    "This mode is for running alongside consumers on a shared docker network (eg. docker-compose). " +
                    "Use --connectivity=host (the default) when running on the host directly."
            )
        }

        val containerId = readOwnContainerId()
            ?: return fail(
                "Running with --connectivity=network, but Nebula's own container id could not be read from /etc/hostname. " +
                    "This mode requires Nebula to run inside a container; use --connectivity=host when running on the host directly."
            )

        val dockerClient = DockerClientFactory.lazyClient()
        val container = try {
            dockerClient.inspectContainerCmd(containerId).exec()
        } catch (e: Exception) {
            return fail(
                "Running with --connectivity=network, but Nebula could not inspect its own container ('$containerId') " +
                    "to determine its docker network: ${e.message}. " +
                    "If a custom hostname is set on the container, /etc/hostname no longer matches the container id."
            )
        }

        val attachedNetworks = container.networkSettings.networks.keys
        return selectAttachedNetwork(attachedNetworks, networkName).fold(
            onSuccess = { name ->
                spec.commandLine().out.println("Nebula detected it is running on docker network '$name' - child containers will be attached to it")
                Result.success(ExistingDockerNetwork(name))
            },
            onFailure = { fail(it.message ?: "Could not determine Nebula's docker network") }
        )
    }

    private fun fail(message: String): Result<Network> {
        spec.commandLine().err.println(message)
        return Result.failure(IllegalStateException(message))
    }

    /**
     * The container id Nebula is running as. Inside a container `/etc/hostname`
     * holds the short container id (unless a custom hostname was set), which
     * docker can resolve for an inspect call.
     */
    private fun readOwnContainerId(): String? =
        runCatching { File("/etc/hostname").readText().trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Best-effort detection of whether Nebula is running inside a container.
     *
     * Docker writes `/.dockerenv` at the container root; podman uses
     * `/run/.containerenv`. As a fallback we scan `/proc/1/cgroup` for a known
     * container runtime - this is unreliable under cgroup v2, hence only a
     * fallback.
     */
    private fun isRunningInContainer(): Boolean {
        if (File("/.dockerenv").exists() || File("/run/.containerenv").exists()) {
            return true
        }
        return runCatching {
            File("/proc/1/cgroup").readText().lineSequence().any { line ->
                line.contains("docker") || line.contains("containerd") || line.contains("kubepods")
            }
        }.getOrDefault(false)
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

fun main(args: Array<String>): Unit =
    exitProcess(CommandLine(Nebula()).setCaseInsensitiveEnumValuesAllowed(true).execute(*args))

/**
 * From the set of networks a container is attached to, selects the single one
 * whose name contains [identifier]. Returns a failure (rather than guessing)
 * when zero or more than one network matches, so callers can surface a clear
 * diagnostic.
 */
fun selectAttachedNetwork(attachedNetworks: Set<String>, identifier: String): Result<String> {
    val matches = attachedNetworks.filter { it.contains(identifier) }
    return when (matches.size) {
        1 -> Result.success(matches.single())
        0 -> Result.failure(
            IllegalStateException(
                "Running with --connectivity=network, but none of the networks Nebula is attached to ($attachedNetworks) " +
                    "contain '$identifier'. Set --network (or NEBULA_NETWORK) to match your network name."
            )
        )
        else -> Result.failure(
            IllegalStateException(
                "Running with --connectivity=network, but Nebula is attached to multiple networks matching '$identifier': $matches. " +
                    "Set --network (or NEBULA_NETWORK) to a more specific name."
            )
        )
    }
}

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