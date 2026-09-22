package com.orbitalhq.nebula.s3

import com.orbitalhq.nebula.InfraDsl
import com.orbitalhq.nebula.resources.StackResources
import com.orbitalhq.nebula.utils.NameGenerator
import lang.taxi.utils.log
import mu.KLogger
import mu.KotlinLogging

private val logger: KLogger = KotlinLogging.logger {}

interface S3Dsl : InfraDsl {
    fun s3(imageName: String = "localstack/localstack:3.0.2", componentName: String = "s3", dsl: S3Builder.(KLogger) -> Unit): S3Executor {
        val builder = S3Builder(imageName, componentName, resources)
        builder.dsl(logger)
        return this.add(S3Executor(builder.build(), loggers = listOf(logger.name)))
    }
}

class S3Builder(
    private val imageName: String,
    private val componentName: String,
    /** The stack's bundle, so `file(path)` can read a file that shipped with the stack. */
    private val stackResources: StackResources
) {
    private val buckets = mutableListOf<BucketConfig>()

    fun bucket(name: String, init: BucketBuilder.() -> Unit) {
        buckets.add(BucketBuilder(name, stackResources).apply(init).build())
    }

    fun build(): S3Config = S3Config(imageName, buckets, componentName = componentName)
}

class BucketBuilder(
    private val name: String,
    /** The stack's bundle, so `file(path)` can read a file that shipped with the stack. */
    private val stackResources: StackResources
) {
    private val resources = mutableListOf<S3Resource>()

    fun file(name: String, content: String) {
        resources.add(InlineFileResource(name, content))
    }
    fun file(name: String, content: Sequence<String>) {
        resources.add(SequenceResource(name, content))
    }

    /**
     * Uploads a file from disk, using its filename as the S3 key.
     *
     * A *relative* path is read from the bundle shipped with the stack, so the
     * file travels with the project rather than having to exist on the Nebula
     * server. An *absolute* path is read from the machine running Nebula, which
     * is what it has always meant.
     *
     * Resolution happens here, while the script is being evaluated, so a typo in
     * a resource name fails the submission with a message listing what the stack
     * actually shipped - rather than failing later, mid-startup.
     */
    fun file(path: String) {
        resources.add(FileResource(stackResources.resolveFilePath(path)))
    }

    fun build(): BucketConfig = BucketConfig(name, resources)
}


// Data classes to hold configurations
data class S3Config(val imageName: String, val buckets: List<BucketConfig>, val componentName: String = NameGenerator.generateName())
data class BucketConfig(val name: String, val resources: List<S3Resource>)

// Sealed class for S3 resources
sealed class S3Resource
data class InlineFileResource(val name: String, val content: String) : S3Resource()
data class FileResource(val path: String) : S3Resource()
data class SequenceResource(
    val name: String,
    val sequence: Sequence<String>,
    val contentType: String = "text/csv",
    // 5MB default - don't use a value smaller than 5MB, which is the minimum
    // size supported by the Multipart uploader SDK
    val bufferSizeInBytes: Int = 5 * 1024 * 1204
) : S3Resource()