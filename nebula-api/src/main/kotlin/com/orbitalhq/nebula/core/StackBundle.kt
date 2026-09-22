package com.orbitalhq.nebula.core

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.Base64

/**
 * A path to a file inside a [StackBundle], relative to the bundle root.
 *
 * Always uses `/` as the separator, and must never be absolute or contain
 * `..` segments - see `StackResources` in nebula-dsl, which enforces this
 * when the bundle is unpacked and again whenever a stack reads from it.
 */
typealias ResourcePath = String

/**
 * How the [ResourceContent.content] string encodes the underlying bytes.
 */
enum class ResourceEncoding {
    /** The content is the file's text, and is written out as UTF-8. */
    TEXT,

    /** The content is the file's bytes, Base64 encoded. */
    BASE64
}

/**
 * The content of a single file shipped alongside a stack script.
 *
 * Text is carried as text (so bundles stay readable and diffable on the wire);
 * binary files are Base64 encoded and flagged via [encoding].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ResourceContent(
    val content: String,
    val encoding: ResourceEncoding = ResourceEncoding.TEXT
) {
    companion object {
        @JvmStatic
        fun text(content: String): ResourceContent = ResourceContent(content, ResourceEncoding.TEXT)

        @JvmStatic
        fun binary(bytes: ByteArray): ResourceContent =
            ResourceContent(Base64.getEncoder().encodeToString(bytes), ResourceEncoding.BASE64)
    }

    /**
     * The decoded bytes of this resource, as they will be written to disk when
     * the bundle is unpacked.
     */
    fun bytes(): ByteArray = when (encoding) {
        ResourceEncoding.TEXT -> content.toByteArray(Charsets.UTF_8)
        ResourceEncoding.BASE64 -> Base64.getDecoder().decode(content)
    }
}

/**
 * A stack script plus the resource files (OpenAPI specs, CSV seed data, ...)
 * that it reads at startup.
 *
 * Submitting a bundle rather than a bare script is what lets a stack read files
 * that live in the *submitter's* project, rather than on the Nebula server's own
 * filesystem. The resources are unpacked into a per-submission temporary
 * directory, and every read from a stack is confined to that directory.
 *
 * Bundles are a superset of the existing script-only submission: a bundle with
 * no resources behaves exactly as `POST /stacks` with the script text does.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StackBundle(
    val script: String,
    val resources: Map<ResourcePath, ResourceContent> = emptyMap()
) {
    companion object {
        /** A bundle carrying just a script, equivalent to the legacy script-only submission. */
        @JvmStatic
        fun scriptOnly(script: String): StackBundle = StackBundle(script, emptyMap())
    }

    /** Derived, so it must not be written to (or read from) the wire. */
    @get:JsonIgnore
    val hasResources: Boolean
        get() = resources.isNotEmpty()
}

/**
 * The limits applied when a [StackBundle] is unpacked.
 *
 * Bundles travel as JSON over the same websocket that carries stack scripts, and
 * are written to the Nebula server's disk, so both the request size and the
 * unpacked footprint are capped. Exceeding any limit fails the submission with a
 * message naming the offending file and the limit.
 */
object StackBundleLimits {
    /** Largest decoded size of any single resource. */
    const val MAX_RESOURCE_BYTES: Long = 10L * 1024 * 1024

    /** Largest total decoded size of all resources in one bundle. */
    const val MAX_BUNDLE_BYTES: Long = 50L * 1024 * 1024

    /** Largest number of files in one bundle. */
    const val MAX_RESOURCE_COUNT: Int = 500
}
