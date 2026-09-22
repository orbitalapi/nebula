package com.orbitalhq.nebula.apigateway

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.orbitalhq.nebula.StackRunner
import com.orbitalhq.nebula.resources.StackResources
import com.orbitalhq.nebula.stack
import com.orbitalhq.nebula.start
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files

class ApiGatewayExecutorTest : DescribeSpec({
    lateinit var infra: StackRunner

    describe("ApiGatewayExecutor") {
        afterTest {
            infra.shutDownAll()
        }

        it("imports an OpenAPI spec as a REST API, deploys it, and exposes it in the component config") {
            infra = stack {
                apiGateway {
                    restApi("pets", stage = "v1", openApi = PETS_OPEN_API)
                }
            }.start()

            val gateway = infra.apiGateway.single()
            val pets = gateway.deployedApis.getValue("pets")
            pets.stage.shouldBe("v1")
            pets.invokeUrl.shouldContain("/restapis/${pets.apiId}/v1/_user_request_")

            // What Orbital's registry sync will pull
            val export = jacksonObjectMapper().readTree(gateway.exportOpenApi("pets"))
            export.at("/paths/~1pets/get").isMissingNode.shouldBe(false)
            export.at("/components/schemas/Pet/properties/name/type").asText().shouldBe("string")

            // What Orbital's env.conf can reference
            val config = gateway.componentInfo!!.componentConfig
            config.shouldContainKey("endpointOverride")
            config.getValue("petsApiId").shouldBe(pets.apiId)
            config.getValue("petsStage").shouldBe("v1")
            config.getValue("petsInvokeUrl").shouldStartWith(config.getValue("endpointOverride"))
        }

        it("reads an OpenAPI spec shipped with the stack as a bundle resource") {
            val bundleDir = Files.createTempDirectory("apigateway-bundle")
            Files.createDirectories(bundleDir.resolve("specs"))
            Files.writeString(bundleDir.resolve("specs/pets.json"), PETS_OPEN_API)

            infra = stack(resources = StackResources.at(bundleDir)) {
                apiGateway {
                    restApiFromFile("pets", path = "specs/pets.json", stage = "v1")
                }
            }.start()

            val export = jacksonObjectMapper().readTree(infra.apiGateway.single().exportOpenApi("pets"))
            export.at("/paths/~1pets/get").isMissingNode.shouldBe(false)
        }
    }
})

private val PETS_OPEN_API = """
{
  "openapi": "3.0.1",
  "info": { "title": "Pets", "version": "1.0" },
  "paths": {
    "/pets": {
      "get": {
        "operationId": "listPets",
        "responses": {
          "200": {
            "description": "The pets",
            "content": { "application/json": { "schema": { "type": "array", "items": { "${'$'}ref": "#/components/schemas/Pet" } } } }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "Pet": {
        "type": "object",
        "required": ["id", "name"],
        "properties": { "id": { "type": "integer" }, "name": { "type": "string" } }
      }
    }
  }
}
""".trimIndent()
