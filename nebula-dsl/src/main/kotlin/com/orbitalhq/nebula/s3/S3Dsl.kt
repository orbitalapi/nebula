package com.orbitalhq.nebula.s3

import com.orbitalhq.nebula.InfraDsl

interface S3Dsl : InfraDsl {
    fun s3(imageName: String = "localstack/localstack:latest", dsl: S3Builder.() -> Unit): S3Executor {
        val builder = S3Builder(imageName)
        builder.dsl()
        return this.add(S3Executor(builder.build()))
    }
}

class S3Builder(private val imageName: String) {
    private val buckets = mutableListOf<BucketConfig>()

    fun bucket(name: String, init: BucketBuilder.() -> Unit) {
        buckets.add(BucketBuilder(name).apply(init).build())
    }

    fun build(): S3Config = S3Config(buckets)
}

class BucketBuilder(private val name: String) {
    private val resources = mutableListOf<S3Resource>()

    fun file(name: String, content: String) {
        resources.add(InlineFileResource(name, content))
    }

    fun file(path: String) {
        resources.add(FileResource(path))
    }

    fun build(): BucketConfig = BucketConfig(name, resources)
}


// Data classes to hold configurations
data class S3Config(val buckets: List<BucketConfig>)
data class BucketConfig(val name: String, val resources: List<S3Resource>)

// Sealed class for S3 resources
sealed class S3Resource
data class InlineFileResource(val name: String, val content: String) : S3Resource()
data class FileResource(val path: String) : S3Resource()