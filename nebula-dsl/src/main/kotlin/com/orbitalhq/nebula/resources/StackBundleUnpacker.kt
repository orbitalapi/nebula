package com.orbitalhq.nebula.resources

import com.orbitalhq.nebula.core.ResourceContent
import com.orbitalhq.nebula.core.StackBundle
import com.orbitalhq.nebula.core.StackBundleLimits
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Raised when a submitted bundle is not safe or not small enough to unpack.
 * Distinct from [StackResourceException], which is raised when a *running* stack
 * asks for something it shouldn't have.
 */
class InvalidStackBundleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Writes a submitted [StackBundle]'s resources into a directory of their own.
 *
 * Every entry is validated before anything is written: the same containment
 * rules [StackResources] applies on read are applied here on write, so a
 * `../../etc/cron.d/evil` entry is rejected rather than landing on disk (the
 * zip-slip class of bug). Size limits are checked against the *decoded* bytes,
 * before any file is created, so an oversized bundle leaves nothing behind.
 */
class StackBundleUnpacker(
    /**
     * The directory under which per-submission bundle directories are created.
     * Defaults to a `nebula-stack-bundles` directory in the system temp dir.
     */
    private val baseDirectory: Path = defaultBaseDirectory()
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        private fun defaultBaseDirectory(): Path {
            val base = Path.of(System.getProperty("java.io.tmpdir")).resolve("nebula-stack-bundles")
            Files.createDirectories(base)
            return base.toRealPath()
        }

        /**
         * A content hash over the bundle's resources. Two bundles with the same
         * files in the same state share a fingerprint, regardless of map ordering.
         */
        fun fingerprintOf(resources: Map<String, ResourceContent>): String {
            if (resources.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256")
            resources.entries.sortedBy { it.key }.forEach { (path, content) ->
                digest.update(path.toByteArray(Charsets.UTF_8))
                digest.update(content.bytes())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Unpacks [bundle]'s resources for the stack named [stackName], returning a
     * [StackResources] confined to the directory they were written to.
     *
     * A bundle with no resources needs no directory, and yields
     * [StackResources.NONE] - so a bundle submission of a bare script behaves
     * exactly as the script-only submission does.
     *
     * @throws InvalidStackBundleException if an entry escapes the bundle, or a
     * size limit is exceeded.
     */
    fun unpack(stackName: String, bundle: StackBundle): StackResources {
        if (!bundle.hasResources) return StackResources.NONE

        validate(stackName, bundle)

        val directory = Files.createTempDirectory(baseDirectory, "${safeDirName(stackName)}-").toRealPath()
        try {
            bundle.resources.forEach { (relativePath, content) ->
                val target = resolveWithin(directory, relativePath)
                Files.createDirectories(target.parent)
                Files.write(target, content.bytes())
            }
        } catch (e: Exception) {
            directory.toFile().deleteRecursively()
            throw when (e) {
                is StackResourceException -> InvalidStackBundleException(e.message ?: "Invalid resource path", e)
                is InvalidStackBundleException -> e
                else -> InvalidStackBundleException(
                    "Failed to unpack resources for stack '$stackName' - ${e.message}", e
                )
            }
        }
        logger.info { "Unpacked ${bundle.resources.size} resources for stack '$stackName' into $directory" }
        return StackResources(
            root = directory,
            ownsRoot = true,
            fingerprint = fingerprintOf(bundle.resources)
        )
    }

    /**
     * Checks every entry before a single byte is written, so a bundle is either
     * fully unpacked or leaves nothing on disk.
     */
    private fun validate(stackName: String, bundle: StackBundle) {
        if (bundle.resources.size > StackBundleLimits.MAX_RESOURCE_COUNT) {
            throw InvalidStackBundleException(
                "Stack '$stackName' ships ${bundle.resources.size} resources, which exceeds the limit of " +
                    "${StackBundleLimits.MAX_RESOURCE_COUNT} files per stack."
            )
        }
        // A throwaway root: validation must reject a path before we create the real
        // directory, and resolveWithin needs something to resolve against.
        val probeRoot = Path.of("/nebula-bundle-probe").normalize()
        var totalBytes = 0L
        bundle.resources.forEach { (relativePath, content) ->
            try {
                resolveWithin(probeRoot, relativePath)
            } catch (e: StackResourceException) {
                throw InvalidStackBundleException(
                    "Stack '$stackName' ships an unusable resource - ${e.message}", e
                )
            }
            val bytes = try {
                content.bytes().size.toLong()
            } catch (e: IllegalArgumentException) {
                throw InvalidStackBundleException(
                    "Resource '$relativePath' in stack '$stackName' is flagged as ${content.encoding} " +
                        "but is not valid Base64 - ${e.message}", e
                )
            }
            if (bytes > StackBundleLimits.MAX_RESOURCE_BYTES) {
                throw InvalidStackBundleException(
                    "Resource '$relativePath' in stack '$stackName' is $bytes bytes, which exceeds the " +
                        "per-file limit of ${StackBundleLimits.MAX_RESOURCE_BYTES} bytes."
                )
            }
            totalBytes += bytes
            if (totalBytes > StackBundleLimits.MAX_BUNDLE_BYTES) {
                throw InvalidStackBundleException(
                    "The resources shipped with stack '$stackName' total more than the bundle limit of " +
                        "${StackBundleLimits.MAX_BUNDLE_BYTES} bytes."
                )
            }
        }
    }

    /** Stack names carry `/` and `[]` from Orbital's workspace + package prefixes. */
    private fun safeDirName(stackName: String): String =
        stackName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "stack" }
}
