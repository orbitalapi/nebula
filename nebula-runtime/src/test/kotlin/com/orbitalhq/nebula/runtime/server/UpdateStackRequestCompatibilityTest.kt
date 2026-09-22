package com.orbitalhq.nebula.runtime.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.orbitalhq.nebula.core.ResourceEncoding
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * `/stream/stacks` is a long-lived contract: an Orbital that predates bundles must
 * keep talking to a Nebula that has them, and vice versa. Both shapes of the
 * message therefore have to deserialise, and both have to normalise to the same
 * thing before the server does anything with them.
 */
class UpdateStackRequestCompatibilityTest : DescribeSpec({

    val objectMapper = jacksonObjectMapper()

    describe("reading a submission off the socket") {

        it("reads the original script-only message") {
            val request = objectMapper.readValue<UpdateStackRSocketRequest>(
                """{"stacks": {"films": "stack {}"}}"""
            )

            request.stacks shouldBe mapOf("films" to "stack {}")
            request.bundles.shouldBeEmpty()
        }

        it("reads a bundle message") {
            val request = objectMapper.readValue<UpdateStackRSocketRequest>(
                """
                {
                  "bundles": {
                    "films": {
                      "script": "stack {}",
                      "resources": {
                        "data/sales.csv": { "content": "Title,Tickets", "encoding": "TEXT" }
                      }
                    }
                  }
                }
                """.trimIndent()
            )

            request.stacks.shouldBeEmpty()
            request.bundles shouldHaveSize 1
            val bundle = request.bundles.getValue("films")
            bundle.script shouldBe "stack {}"
            bundle.resources.getValue("data/sales.csv").content shouldBe "Title,Tickets"
            bundle.resources.getValue("data/sales.csv").encoding shouldBe ResourceEncoding.TEXT
        }

        it("defaults a resource with no encoding to text") {
            val request = objectMapper.readValue<UpdateStackRSocketRequest>(
                """{"bundles": {"films": {"script": "stack {}", "resources": {"a.txt": {"content": "hi"}}}}}"""
            )

            request.bundles.getValue("films").resources.getValue("a.txt").encoding shouldBe ResourceEncoding.TEXT
        }

        it("reads a bundle with no resources") {
            val request = objectMapper.readValue<UpdateStackRSocketRequest>(
                """{"bundles": {"films": {"script": "stack {}"}}}"""
            )

            request.bundles.getValue("films").hasResources shouldBe false
        }
    }

    describe("normalising to bundles") {
        it("turns a script-only entry into a bundle with no resources") {
            val request = UpdateStackRSocketRequest(stacks = mapOf("films" to "stack {}"))

            val bundles = request.allBundles()

            bundles shouldHaveSize 1
            bundles.getValue("films").script shouldBe "stack {}"
            bundles.getValue("films").hasResources shouldBe false
        }

        it("keeps both kinds of entry when a message carries each") {
            val request = objectMapper.readValue<UpdateStackRSocketRequest>(
                """
                {
                  "stacks": { "plain": "stack {}" },
                  "bundles": { "bundled": { "script": "stack {}", "resources": { "a.txt": { "content": "hi" } } } }
                }
                """.trimIndent()
            )

            val bundles = request.allBundles()

            bundles.keys shouldBe setOf("plain", "bundled")
            bundles.getValue("plain").hasResources shouldBe false
            bundles.getValue("bundled").hasResources shouldBe true
        }
    }
})
