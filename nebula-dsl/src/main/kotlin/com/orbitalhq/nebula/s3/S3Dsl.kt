package com.orbitalhq.nebula.s3

import com.orbitalhq.nebula.InfraDsl
import com.orbitalhq.nebula.utils.NameGenerator

interface S3Dsl : InfraDsl {
    fun s3(imageName: String = "localstack/localstack:latest", componentName: String = "s3", dsl: S3Builder.() -> Unit): S3Executor {
        val builder = S3Builder(imageName, componentName)
        builder.dsl()
        return this.add(S3Executor(builder.build()))
    }
}

class S3Builder(private val imageName: String, private val componentName: String) {
    private val buckets = mutableListOf<BucketConfig>()

    fun bucket(name: String, init: BucketBuilder.() -> Unit) {
        buckets.add(BucketBuilder(name).apply(init).build())
    }

    fun build(): S3Config = S3Config(imageName, buckets, componentName = componentName)
}

class BucketBuilder(private val name: String) {
    private val resources = mutableListOf<S3Resource>()

    fun file(name: String, content: String) {
        resources.add(InlineFileResource(name, content))
    }
    fun file(name: String, content: Sequence<String>) {
        resources.add(SequenceResource(name, content))
    }

    fun file(path: String) {
        resources.add(FileResource(path))
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