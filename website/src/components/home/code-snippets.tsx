import {CodeSnippetMap} from "@/components/Guides/CodeSnippet";

export const nebulaExampleSnippets: CodeSnippetMap = {
  'kafka-and-http': {
    name: 'stack.nebula.kts',
    lang: 'kotlin',
    code: `stack {
   // Start a Kafka broker which emits a message every 100ms
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

   // start an HTTP server which responds on /hello
   http {
     get("/hello") { call ->
        call.respondText("Hello, World!")
   }
}`
  },
};

