package com.orbitalhq.nebula.apigateway

import com.orbitalhq.nebula.InfraDsl
import com.orbitalhq.nebula.resources.StackResources
import mu.KLogger
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger: KLogger = KotlinLogging.logger {}

/**
 * Declares an AWS API Gateway (on LocalStack) holding REST APIs imported from OpenAPI specs.
 *
 * Orbital treats API Gateway as a schema registry: it browses the deployed stages and pulls
 * their OpenAPI exports into a project. This component gives a stack something for it to pull.
 */
interface ApiGatewayDsl : InfraDsl {
    fun apiGateway(
        imageName: String = "localstack/localstack:3.0.2",
        componentName: String = "apiGateway",
        dsl: ApiGatewayBuilder.(KLogger) -> Unit
    ): ApiGatewayExecutor {
        val builder = ApiGatewayBuilder(imageName, componentName, resources)
        builder.dsl(logger)
        return this.add(ApiGatewayExecutor(builder.build(), loggers = listOf(logger.name)))
    }
}

class ApiGatewayBuilder(
    private val imageName: String,
    private val componentName: String,
    /** The stack's bundle, so `restApiFromFile(path)` can read a spec that shipped with the stack. */
    private val stackResources: StackResources
) {
    private val apis = mutableListOf<RestApiConfig>()

    /**
     * Imports [openApi] (an OpenAPI 3 document, JSON or YAML) as a REST API and deploys it to [stage].
     *
     * [name] is how the API is referred to in the emitted config (eg `petsApiId`), so keep it
     * to letters and digits.
     */
    fun restApi(name: String, openApi: String, stage: String = "prod") {
        apis.add(RestApiConfig(name, stage, openApi))
    }

    /**
     * As [restApi], reading the OpenAPI document from [path].
     *
     * A relative path resolves through the stack's bundle (the files shipped alongside the
     * script). An absolute path is read from the machine running Nebula.
     */
    fun restApiFromFile(name: String, path: String, stage: String = "prod") {
        restApi(name, Files.readString(Path.of(stackResources.resolveFilePath(path))), stage)
    }

    fun build(): ApiGatewayConfig = ApiGatewayConfig(imageName, apis, componentName)
}

data class ApiGatewayConfig(val imageName: String, val apis: List<RestApiConfig>, val componentName: String)

data class RestApiConfig(val name: String, val stage: String, val openApi: String) {
    init {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_-]*"))) {
            "REST API name '$name' must start with a letter and contain only letters, digits, dashes and underscores"
        }
    }
}
