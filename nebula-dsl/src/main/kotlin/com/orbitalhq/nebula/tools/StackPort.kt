package com.orbitalhq.nebula.tools

import com.orbitalhq.nebula.uniqueNetworkHost
import org.testcontainers.localstack.LocalStackContainer

const val STACKPORT_IMAGE = "davireis/stackport:0.4.4"

/**
 * StackPort (https://github.com/DaviReisVieira/stackport) - a browser for AWS resources,
 * pointed at a LocalStack container over the Nebula network.
 *
 * @param description what the user would use it for on this component
 */
fun stackPortTool(
    localstack: LocalStackContainer,
    internalPort: Int,
    description: String
) = ToolDefinition(
    id = "stackport",
    displayName = "StackPort",
    description = description,
    launch = ContainerToolSpec(
        image = STACKPORT_IMAGE,
        env = mapOf(
            "AWS_ENDPOINT_URL" to "http://${localstack.uniqueNetworkHost}:$internalPort",
            "AWS_ACCESS_KEY_ID" to localstack.accessKey,
            "AWS_SECRET_ACCESS_KEY" to localstack.secretKey,
            "AWS_REGION" to localstack.region,
        ),
        httpPort = 8080,
        readinessPath = "/api/health"
    )
)
