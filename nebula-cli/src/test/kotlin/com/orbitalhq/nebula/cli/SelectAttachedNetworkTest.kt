package com.orbitalhq.nebula.cli

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class SelectAttachedNetworkTest : DescribeSpec({
    describe("selectAttachedNetwork") {
        it("selects the single network whose name contains the identifier") {
            selectAttachedNetwork(setOf("bridge", "project-a_nebula_network"), "nebula_network")
                .getOrNull() shouldBe "project-a_nebula_network"
        }

        it("matches a compose-prefixed network name") {
            selectAttachedNetwork(setOf("project-b_nebula_network"), "nebula_network")
                .getOrNull() shouldBe "project-b_nebula_network"
        }

        it("fails when no attached network matches") {
            selectAttachedNetwork(setOf("bridge", "host"), "nebula_network")
                .isFailure shouldBe true
        }

        it("fails when more than one attached network matches") {
            val result = selectAttachedNetwork(
                setOf("project-a_nebula_network", "project-b_nebula_network"),
                "nebula_network"
            )
            result.isFailure shouldBe true
        }
    }
})
