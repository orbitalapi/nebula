package com.orbitalhq.nebula

import com.orbitalhq.nebula.utils.duration
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import kotlin.random.Random

class InfraSpecTest : DescribeSpec({
    describe("InfraSpec") {
        it("should build components") {
            val spec = services {
                kafka {
                    producer("100ms".duration(), "stockQuotes") {
                        message {
                            mapOf(
                                "symbol" to listOf("GBP/USD", "AUD/USD", "NZD/USD").random(),
                                "price" to Random.nextDouble(0.8, 0.95).toBigDecimal()
                            )
                        }
                    }
                }
            }
            spec.components.shouldHaveSize(1)
        }

    }
}

)