package com.orbitalhq.nebula.resources

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.stream.Collectors
import kotlin.io.path.isDirectory

/**
 * Raised when a stack asks for a resource that doesn't exist, or that would
 * escape the bundle directory.
 */
class StackResourceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The files shipped alongside a stack script, unpacked into a directory that
 * belongs to this submission alone.
 *
 * Every lookup is confined to [root]:
 *
 *  - absolute paths are rejected,
 *  - `..` segments are rejected, both before and after normalisation,
 *  - the resolved path is turned into a *real* path (following symlinks) and
 *    checked to still be a descendant of the real [root].
 *
 * That last step is what stops a symlink planted inside the bundle (or a bundle
 * entry crafted to point at one) from reading `/etc/ssh/id_rsa` or any other
 * file on the Nebula server.
 *
 * Note that these checks confine *resource* access. A Nebula script is arbitrary
 * Kotlin and can always open files directly; the guarantee here is that a
 * bundle-relative path resolves to a file inside the bundle, nothing more and
 * nothing less.
 */
class StackResources internal constructor(
    /**
     * The directory this bundle was unpacked into, or null when the stack was
     * submitted without a bundle (a plain script submission, or the CLI).
     */
    val root: Path?,
    /**
     * Whether [delete] should remove [root]. True for directories we created by
     * unpacking a bundle; false for a directory handed to us by a caller.
     */
    private val ownsRoot: Boolean,
    /**
     * A stable identity for the content of this bundle. [StackRunner] compares it
     * alongside the script source, so resubmitting an unchanged script with
     * *changed* resources restarts the stack rather than being treated as a
     * duplicate.
     */
    val fingerprint: String
) {
    companion object {
        /**
         * The resources of a stack submitted without a bundle. Reading from it
         * fails with a message pointing at bundle submission, rather than
         * silently resolving against the Nebula server's working directory.
         */
        @JvmStatic
        val NONE = StackResources(root = null, ownsRoot = false, fingerprint = "")

        /**
         * A read-only view over an existing directory. Used by the CLI (where the
         * script's own directory is the bundle) and by tests.
         */
        @JvmStatic
        fun at(directory: Path): StackResources {
            require(directory.isDirectory()) { "$directory is not a directory" }
            val real = directory.toRealPath()
            return StackResources(
                root = real,
                ownsRoot = false,
                fingerprint = fingerprintOfDirectory(real)
            )
        }

        private fun fingerprintOfDirectory(root: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            filesUnder(root).sortedBy { root.relativize(it).toString() }
                .forEach { file ->
                    digest.update(root.relativize(file).joinToString("/").toByteArray(Charsets.UTF_8))
                    digest.update(Files.readAllBytes(file))
                }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun filesUnder(root: Path): List<Path> =
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) }.collect(Collectors.toList())
            }
    }

    /** True when this stack was submitted with a resource bundle. */
    val hasBundle: Boolean
        get() = root != null

    /**
     * The relative paths of every file in the bundle, `/`-separated and sorted.
     * Useful in error messages, and for scripts that want to iterate a directory.
     */
    val names: List<String>
        get() {
            val root = this.root ?: return emptyList()
            return filesUnder(root).map { root.relativize(it).joinToString("/") }.sorted()
        }

    /**
     * Resolves [relativePath] to a real path inside the bundle.
     *
     * @throws StackResourceException if there is no bundle, the path is absolute
     * or escapes the bundle, or no such file exists.
     */
    fun path(relativePath: String): Path {
        val root = this.root
            ?: throw StackResourceException(
                "Cannot read resource '$relativePath': this stack was submitted without a resource bundle. " +
                    "Place the file alongside the stack script in the project's nebula directory, " +
                    "or submit the stack through the bundle API."
            )
        val resolved = resolveWithin(root, relativePath)
        if (!Files.exists(resolved)) {
            throw StackResourceException(
                "No resource named '$relativePath' was shipped with this stack. " +
                    "Available resources: ${names.joinToString(", ").ifEmpty { "(none)" }}"
            )
        }
        // toRealPath follows symlinks. A link planted inside the bundle that points
        // outside it normalises cleanly above, and is only caught here.
        val real = try {
            resolved.toRealPath()
        } catch (e: IOException) {
            throw StackResourceException("Failed to resolve resource '$relativePath' - ${e.message}", e)
        }
        if (!real.startsWith(root)) {
            throw StackResourceException(
                "Resource '$relativePath' resolves to $real, which is outside this stack's resource bundle. " +
                    "A stack may only read the files shipped with it."
            )
        }
        return real
    }

    /** True when [relativePath] names a readable file in the bundle. Never throws. */
    fun exists(relativePath: String): Boolean = try {
        path(relativePath)
        true
    } catch (e: StackResourceException) {
        false
    }

    /** The UTF-8 text of a resource shipped with this stack. */
    fun readText(relativePath: String): String = Files.readString(path(relativePath))

    /** The raw bytes of a resource shipped with this stack. */
    fun readBytes(relativePath: String): ByteArray = Files.readAllBytes(path(relativePath))

    /**
     * Resolves a path given to a DSL function that reads a file
     * (eg. `s3 { bucket("data") { file("seed/sales.csv") } }`).
     *
     * Relative paths resolve through the bundle when one is present, so a stack
     * ships its own data. Absolute paths keep their existing meaning - a file on
     * the machine running Nebula - so stacks written before bundles existed, and
     * stacks run from the CLI against local files, behave exactly as they did.
     */
    fun resolveFilePath(path: String): String {
        if (!hasBundle) return path
        return if (isAbsolute(path)) path else path(path).toString()
    }

    /**
     * Deletes the unpacked bundle, if this instance owns it. Called when the stack
     * is removed or replaced. Safe to call more than once.
     */
    fun delete() {
        val root = this.root ?: return
        if (!ownsRoot) return
        // deleteRecursively() walks bottom-up and does not follow symlinks, so a
        // link inside the bundle is unlinked rather than followed and emptied.
        root.toFile().deleteRecursively()
    }

    override fun toString(): String =
        if (hasBundle) "StackResources($root, ${names.size} files)" else "StackResources(no bundle)"
}

/**
 * Rejects anything that isn't a plain relative path, then resolves it against
 * [root] and checks the result is still inside [root].
 *
 * Shared by [StackResources] (reads) and [StackBundleUnpacker] (writes), so an
 * entry that could not be read can never be written in the first place.
 */
internal fun resolveWithin(root: Path, relativePath: String): Path {
    if (relativePath.isBlank()) {
        throw StackResourceException("A resource path may not be blank")
    }
    if (isAbsolute(relativePath)) {
        throw StackResourceException(
            "Resource path '$relativePath' is absolute. Resources are addressed by their path " +
                "relative to the stack's nebula directory."
        )
    }
    val candidate = try {
        Paths.get(relativePath)
    } catch (e: InvalidPathException) {
        throw StackResourceException("Resource path '$relativePath' is not a valid path - ${e.message}", e)
    }
    if (candidate.any { it.toString() == ".." }) {
        throw StackResourceException(
            "Resource path '$relativePath' contains a '..' segment. A stack may not reference " +
                "files outside its resource bundle."
        )
    }
    val resolved = root.resolve(candidate).normalize()
    if (!resolved.startsWith(root)) {
        throw StackResourceException(
            "Resource path '$relativePath' resolves outside the stack's resource bundle."
        )
    }
    return resolved
}

/**
 * True for paths that address the host filesystem directly.
 *
 * Deliberately stricter than [Path.isAbsolute]: on Linux a Windows-style
 * `C:\keys\id_rsa` or a UNC `\\host\share` is a *valid relative filename*, and
 * we'd rather reject it than quietly create a strangely-named file.
 */
internal fun isAbsolute(path: String): Boolean {
    if (path.startsWith("/") || path.startsWith("\\")) return true
    if (path.length >= 2 && path[1] == ':' && path[0].isLetter()) return true
    return try {
        Paths.get(path).isAbsolute
    } catch (e: InvalidPathException) {
        false
    }
}
