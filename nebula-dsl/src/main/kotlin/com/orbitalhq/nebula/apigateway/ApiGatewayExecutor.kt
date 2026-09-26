package com.orbitalhq.nebula.apigateway

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
import com.orbitalhq.nebula.tools.ProvidesTools
import com.orbitalhq.nebula.tools.ToolDefinition
import com.orbitalhq.nebula.tools.stackPortTool
import mu.KotlinLogging
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import reactor.core.publisher.Flux
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.apigateway.ApiGatewayClient
import software.amazon.awssdk.services.apigateway.model.PutMode

private val logger = KotlinLogging.logger {}

val StackRunner.apiGateway: List<ApiGatewayExecutor>
    get() {
        return this.component<ApiGatewayExecutor>()
    }

/**
 * A REST API deployed on the gateway. [invokeUrl] is the stage's base URL as seen by consumers.
 */
data class DeployedRestApi(val name: String, val apiId: String, val stage: String, val deploymentId: String, val invokeUrl: String)

/**
 * Runs LocalStack with API Gateway enabled, imports each declared OpenAPI document as a REST API,
 * and deploys it to its stage.
 *
 * The emitted config is flat so Orbital can expose every value as an env variable, eg
 * `NEBULA_API_GATEWAY_ENDPOINT_OVERRIDE` and `NEBULA_API_GATEWAY_PETS_API_ID`.
 */
class ApiGatewayExecutor(private val config: ApiGatewayConfig, loggers: List<LoggerName>) : InfrastructureComponent<Map<String, String>>, ProvidesTools {
    private lateinit var localstack: LocalStackContainer
    lateinit var client: ApiGatewayClient
        private set

    /** Keyed by the name given in the DSL. */
    var deployedApis: Map<String, DeployedRestApi> = emptyMap()
        private set

    override val type = "apiGateway"
    override val name = config.componentName

    override val logStream: LogStream = LogStream(name, slf4jLoggerNames = loggers + listOf(ApiGatewayExecutor::class))
    private val eventSource = ComponentLifecycleEventSource(logStream = logStream)
    override val lifecycleEvents: Flux<ComponentLifecycleEvent> = eventSource.events
    override val currentState: ComponentLifecycleEvent
        get() = eventSource.currentState
    override var componentInfo: ComponentInfo<Map<String, String>>? = null
        private set

    override fun start(nebulaConfig: NebulaConfig, hostConfig: HostConfig): ComponentInfo<Map<String, String>> {
        localstack = LocalStackContainer(DockerImageName.parse(config.imageName))
            // Always enable STS, as Orbital uses it for healthchecks
            .withServices("apigateway", "sts")
            .withNetwork(nebulaConfig.network)
            .withNetworkAliases(config.componentName)
            // Lets x-amazon-apigateway-integration proxies reach services running where Nebula runs,
            // such as an http { } stub, via http://host.docker.internal:<port>
            .withExtraHost("host.docker.internal", "host-gateway")

        eventSource.startContainerAndEmitEvents(localstack, name)

        // Nebula's own client connects via the host-mapped endpoint.
        val endpointOverride = localstack.endpoint
        client = ApiGatewayClient.builder()
            .endpointOverride(endpointOverride)
            .credentialsProvider { AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey) }
            .region(Region.of(localstack.region))
            .build()

        // The emitted endpoint swaps the host-mapped coordinates for the resolved ones.
        val endpoint = nebulaConfig.endpointFor(localstack, config.componentName, LOCALSTACK_INTERNAL_PORT)
        val emittedEndpointOverride = endpointOverride.toASCIIString().replace(
            "${localstack.host}:${localstack.getMappedPort(LOCALSTACK_INTERNAL_PORT)}",
            endpoint.hostAndPort
        )

        deployedApis = config.apis.associate { api -> api.name to importAndDeploy(api, emittedEndpointOverride) }

        val emittedConfig = mapOf(
            "accessKey" to localstack.accessKey,
            "secretKey" to localstack.secretKey,
            "region" to localstack.region,
            "endpointOverride" to emittedEndpointOverride,
        ) + deployedApis.values.flatMap { api ->
            val prefix = lowerCamel(api.name)
            listOf(
                "${prefix}ApiId" to api.apiId,
                "${prefix}Stage" to api.stage,
                "${prefix}InvokeUrl" to api.invokeUrl,
            )
        }

        componentInfo = ComponentInfo(
            containerInfoFrom(localstack, endpoint.host),
            emittedConfig,
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

    override fun tools(): List<ToolDefinition> = listOf(
        stackPortTool(localstack, LOCALSTACK_INTERNAL_PORT, "Browse the deployed REST APIs")
    )

    /** The stage's OpenAPI 3 export, as API Gateway serves it to Orbital. */
    fun exportOpenApi(apiName: String): String {
        val api = deployedApis[apiName] ?: error("No REST API named '$apiName' is declared. Known: ${deployedApis.keys}")
        return client.getExport {
            it.restApiId(api.apiId).stageName(api.stage).exportType("oas30").accepts("application/json")
        }.body().asUtf8String()
    }

    private fun importAndDeploy(api: RestApiConfig, endpointOverride: String): DeployedRestApi {
        // Create the API with the id we want (LocalStack takes it from the _custom_id_ tag), then
        // import the spec into it. importRestApi alone would mint a fresh id on every start.
        val apiId = client.createRestApi {
            it.name(api.name).tags(mapOf(CUSTOM_ID_TAG to api.apiId))
        }.id()
        if (apiId != api.apiId) {
            logger.warn { "Requested id '${api.apiId}' for REST API ${api.name} but the gateway assigned '$apiId'; it will change on the next start" }
        }
        client.putRestApi {
            it.restApiId(apiId).mode(PutMode.OVERWRITE).failOnWarnings(false).body(SdkBytes.fromUtf8String(api.openApi))
        }
        val deploymentId = client.createDeployment { it.restApiId(apiId).stageName(api.stage) }.id()
        // LocalStack's stage URL. A real gateway uses https://{id}.execute-api.{region}.amazonaws.com/{stage}.
        val invokeUrl = "${endpointOverride.trimEnd('/')}/restapis/$apiId/${api.stage}/_user_request_"
        return DeployedRestApi(api.name, apiId, api.stage, deploymentId, invokeUrl)
    }

    // "pet-store" -> "petStore", so the emitted key becomes NEBULA_API_GATEWAY_PET_STORE_API_ID on the Orbital side
    private fun lowerCamel(name: String): String = name.split('-', '_')
        .filter { it.isNotEmpty() }
        .mapIndexed { index, part -> if (index == 0) part.replaceFirstChar { it.lowercase() } else part.replaceFirstChar { it.uppercase() } }
        .joinToString("")
}

// The (edge) port LocalStack listens on inside the container.
private const val LOCALSTACK_INTERNAL_PORT = 4566

// LocalStack assigns a resource the id given in this tag instead of generating one.
private const val CUSTOM_ID_TAG = "_custom_id_"
