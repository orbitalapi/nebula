package com.orbitalhq.nebula.s3

import com.orbitalhq.nebula.HostConfig
import com.orbitalhq.nebula.InfrastructureComponent
import com.orbitalhq.nebula.NebulaConfig
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.containerInfoFrom
import com.orbitalhq.nebula.core.ComponentInfo
import com.orbitalhq.nebula.core.ComponentLifecycleEvent
import com.orbitalhq.nebula.endpointFor
import com.orbitalhq.nebula.events.ComponentLifecycleEventSource
import com.orbitalhq.nebula.logging.LogStream
import com.orbitalhq.nebula.logging.LoggerName
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Flux
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.nio.file.Paths

val StackRunner.s3: List<S3Executor>
    get() {
        return this.component<S3Executor>()
    }

class S3Executor(private val config: S3Config, loggers: List<LoggerName>) : InfrastructureComponent<LocalstackContainerConfig> {
    private lateinit var localstack: LocalStackContainer
    lateinit var s3Client: S3Client
        private set

    override val type = "s3"
    override val name = config.componentName


    override val logStream: LogStream = LogStream(name, slf4jLoggerNames = loggers + listOf(S3Executor::class))
    private val eventSource = ComponentLifecycleEventSource(logStream = logStream)
    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = eventSource.events
    override val currentState: ComponentLifecycleEvent
        get() {
            return eventSource.currentState
        }
    override var componentInfo: ComponentInfo<LocalstackContainerConfig>? = null
        private set

    override fun start(nebulaConfig: NebulaConfig, hostConfig: HostConfig): ComponentInfo<LocalstackContainerConfig> {
        localstack = LocalStackContainer(DockerImageName.parse(config.imageName))
            // Always enable STS, as Orbital uses it for healthchecks
            .withServices(LocalStackContainer.Service.S3, LocalStackContainer.Service.STS)
            .withNetwork(nebulaConfig.network)
            .withNetworkAliases(config.componentName)

        eventSource.startContainerAndEmitEvents(localstack, name)
        // Nebula's own client connects via the host-mapped endpoint.
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

        // The emitted endpoint swaps the host-mapped coordinates for the resolved ones.
        val endpoint = nebulaConfig.endpointFor(localstack, config.componentName, LOCALSTACK_INTERNAL_PORT)
        val emittedEndpointOverride = endpointOverride.toASCIIString().replace(
            "${localstack.host}:${localstack.getMappedPort(LOCALSTACK_INTERNAL_PORT)}",
            endpoint.hostAndPort
        )

        componentInfo = ComponentInfo(
            containerInfoFrom(localstack, endpoint.host),
            LocalstackContainerConfig(
                accessKey = localstack.accessKey,
                secretKey = localstack.secretKey,
                host = endpoint.host,
                port = endpoint.port,
                endpointOverride = emittedEndpointOverride
            ),
            type = type,
            name = name,
            id = id

        )

        // re-emit the running event now we're fully configured
        eventSource.running()
        return componentInfo!!
    }

    override fun stop() {
        eventSource.stopContainerAndEmitEvents(localstack)
    }

    private fun createBucket(bucketConfig: BucketConfig) {
        s3Client.createBucket { it.bucket(bucketConfig.name) }
    }

    private fun uploadResources(bucketConfig: BucketConfig) {
        bucketConfig.resources.forEach { resource ->
            when (resource) {
                is InlineFileResource -> uploadInlineFile(bucketConfig.name, resource)
                is FileResource -> uploadFile(bucketConfig.name, resource)
                is SequenceResource -> uploadSequence(bucketConfig.name, resource)
            }
        }
    }

    private fun uploadSequence(bucketName: String, resource: SequenceResource) {
        S3SequenceUploader(bucketName, resource, s3Client)
            .upload()
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
    val host: String,
    val port: Int,
    val endpointOverride: String
)

// The (edge) port LocalStack listens on inside the container.
private const val LOCALSTACK_INTERNAL_PORT = 4566