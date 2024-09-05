package com.orbitalhq.nebula.s3

import com.orbitalhq.nebula.ComponentInfo
import com.orbitalhq.nebula.ContainerInfo
import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.StackRunner
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.nio.file.Paths

val StackRunner.s3: List<S3Executor>
    get() {
        return this.component<S3Executor>()
    }

class S3Executor(private val config: S3Config) : InfrastructureComponent<LocalstackContainerConfig> {
    private lateinit var localstack: LocalStackContainer
    lateinit var s3Client: S3Client
        private set

    override val type: String = "s3"

    override fun start():ComponentInfo<LocalstackContainerConfig> {
        localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:latest"))
            .withServices(LocalStackContainer.Service.S3)
        localstack.start()

        val endpointOverride = localstack.getEndpointOverride(LocalStackContainer.Service.S3)
        s3Client = S3Client.builder()
            .endpointOverride(endpointOverride)
            .credentialsProvider { AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey) }
            .region(Region.of(localstack.region))
            .build()

        config.buckets.forEach { bucketConfig ->
            createBucket(bucketConfig)
            uploadResources(bucketConfig)
        }

        return ComponentInfo(
            ContainerInfo.from(localstack),
            LocalstackContainerConfig(
                accessKey = localstack.accessKey,
                secretKey = localstack.secretKey,
                endpointOverride = endpointOverride.toASCIIString()
            )
        )
    }

    override fun stop() {
        localstack.stop()
    }

    private fun createBucket(bucketConfig: BucketConfig) {
        s3Client.createBucket { it.bucket(bucketConfig.name) }
    }

    private fun uploadResources(bucketConfig: BucketConfig) {
        bucketConfig.resources.forEach { resource ->
            when (resource) {
                is InlineFileResource -> uploadInlineFile(bucketConfig.name, resource)
                is FileResource -> uploadFile(bucketConfig.name, resource)
            }
        }
    }

    private fun uploadInlineFile(bucketName: String, resource: InlineFileResource) {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucketName)
                .key(resource.name)
                .build(),
            RequestBody.fromString(resource.content)
        )
    }

    private fun uploadFile(bucketName: String, resource: FileResource) {
        val fileName = Paths.get(resource.path).fileName.toString()
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName)
                .build(),
            RequestBody.fromFile(Paths.get(resource.path))
        )
    }

    fun getObjectContent(bucketName: String, key: String): String {
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(key)
            .build()

        s3Client.getObject(getObjectRequest).use { response ->
            return response.readAllBytes().toString(Charsets.UTF_8)
        }
    }
}

// placeholder until I work out what's needed here
data class LocalstackContainerConfig(
    val accessKey: String,
    val secretKey: String,
    val endpointOverride: String
)