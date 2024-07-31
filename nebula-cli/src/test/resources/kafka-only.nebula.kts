import com.orbitalhq.nebula.utils.duration

services {
    kafka {
        producer("100ms".duration(), "stockQuotes") {
            jsonMessage {
                mapOf(
                    "symbol" to listOf("GBP/USD", "AUD/USD", "NZD/USD").random(),
                    "price" to Random.nextDouble(0.8, 0.95).toBigDecimal()
                )
            }
        }
    }
}