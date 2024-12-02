# Nebula - Test ecosystems
Nebula is a DSL for quickly defining test ecosystems - a combination of 
docker images and associated behaviour.

Use Nebula when you want to spin up some containers, and prepare them
with state or behaviour.

For example:

```kotlin
stack {
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
}
```

Under the hood, Nebular uses TestContainers to pull in and start 
the relevant containers, then scripts them.

Use Nebula to:
 * Define a Kafka broker that emits a message periodically
 * Deploy a test S3 instance on `localstack` with preconfigured content
 * Script the deployment of a full db, primed with data, which accepts writes

Nebula is part of the test and demo infrastructure at [Orbital](https://orbitalhq.com) 

## Running as a docker container

 * [orbitalhq/nebula](https://hub.docker.com/r/orbitalhq/nebula)

This starts Nebula as in http server mode, listening on port 8099

```bash
docker run -v /var/run/docker.sock:/var/run/docker.sock --privileged --network host orbitalhq/nebula:latest
```

 * Because nebula launches other docker images, we need `privileged` access, along with access to the docker daemon
 * Also, for you to be able to communicate with the downloaded images, the container runs within the `host` network


