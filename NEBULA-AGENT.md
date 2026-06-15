# NEBULA-AGENT.md

Instructions for AI agents that write **Nebula stacks** — Kotlin-scripted test/demo ecosystems used by Orbital.

Read this document top-to-bottom before writing your first stack. It is self-contained: every DSL function, import, and convention you need is here.

---

## 1. What a Nebula stack is

A Nebula stack is a single Kotlin script that stands up a set of Dockerised infrastructure components (Kafka, Postgres, HTTP servers, S3 on LocalStack, MongoDB, Hazelcast, etc.) with seeded data and live behaviours (producers, endpoints). Orbital uses stacks to drive integration demos and local dev against realistic pipelines.

**File extension, naming, location:**

- File extension MUST be `.nebula.kts`. The script is compiled as a `NebulaScript` — the extension is how the Kotlin scripting host recognises it.
- **Convention: the file is named `stack.nebula.kts`.** A stack file that is not called `stack` is unusual — only deviate if the user explicitly asks.
- Each file produces exactly **one** top-level `stack { ... }` block. Do not write multiple `stack { }` blocks in one file.

---

## 2. Placement inside an Orbital taxi project

Nebula is always consumed by Orbital inside a taxi project. The stack file MUST be discoverable by `taxi.conf`.

**Required wiring in `taxi.conf`:**

```hocon
name: com.acme/csv-to-api
version: 0.1.0
sourceRoot: src/
additionalSources: {
   "@orbital/config" : "orbital/config/*.conf",
   "@orbital/nebula" : "orbital/nebula/*.nebula.kts"
}
dependencies: {
   "com.orbitalhq/core" : "github:orbitalapi/orbital-core-taxi#0.34.0"
}
```

**Rules:**
- The `@orbital/nebula` key under `additionalSources` is **mandatory**. Without it Orbital will not discover the stack.
- By convention the file lives at `orbital/nebula/stack.nebula.kts` (preferred) or `nebula/stack.nebula.kts`.
- Declare the glob with a wildcard (`*.nebula.kts`), not a single explicit file — future stacks in the same folder will be picked up automatically.
- If `taxi.conf` already exists and lacks the `@orbital/nebula` entry, add it. Do not remove unrelated `additionalSources` keys.

**Minimal project layout:**

```
my-project/
├── taxi.conf
├── src/
│   └── ... (taxi files)
└── orbital/
    └── nebula/
        └── stack.nebula.kts
```

---

## 3. Script structure

The entire script is a Kotlin file. You may freely:
- import additional classes,
- declare `data class`, `enum class`, top-level `val`, top-level `fun`,
- use any JVM library that is on Nebula's classpath (Jackson, Ktor server types, JOOQ, Avro, Wire/Protobuf, kotlinx-coroutines, Reactor — all available without extra setup).

Everything runtime-related goes **inside** the `stack { ... }` block at the bottom.

```kotlin
// 1. Imports (if not covered by defaults — see §4)
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.orbitalhq.nebula.utils.duration

// 2. Optional top-level data/types/helpers
data class Customer(val id: String, val name: String)
val customers = listOf(Customer("C-1", "Jane"))

// 3. Exactly one stack block
stack {
   // component declarations go here
   http { /* ... */ }
   kafka { /* ... */ }
   postgres { /* ... */ }
}
```

You can also declare `data class`, `val`, and `fun` *inside* the `stack { }` block — both styles appear across real stacks. Put data classes at the top level if they are reused across components; put them inside `stack` if they are a local implementation detail.

---

## 4. Default imports (do NOT re-import)

The compilation config auto-imports:

- `com.orbitalhq.nebula.stack` — the top-level `stack { }` function
- `kotlin.random.Random`
- `io.ktor.server.request.*` — gives you `call.receiveText()`, `call.request.*`, etc.
- `io.ktor.server.response.*` — gives you `call.respondText()`, `call.respond()`, etc.

Do not re-import these. Everything else must be imported explicitly. Common extra imports you WILL need:

```kotlin
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.orbitalhq.nebula.utils.duration           // "500ms".duration() -> kotlin.time.Duration
import com.orbitalhq.nebula.kafka.MessageSerializer  // .ByteArray / .String
import com.orbitalhq.nebula.io.avro.avroSchema
import com.orbitalhq.nebula.io.protobuf.protobufSchema
import io.ktor.http.*                                // HttpStatusCode, ContentType
import kotlin.time.Duration.Companion.milliseconds   // 100.milliseconds syntax (alternative to "100ms".duration())
```

---

## 5. Components — full reference

All components are added by calling DSL functions inside `stack { }`. Each has an optional `componentName` parameter — set it when you have **more than one of the same type** (e.g. two Postgres databases) so each has a stable identifier.

### 5.1 `http { ... }` — HTTP server (Ktor)

```kotlin
http {
   get("/flights") { call ->
      call.respondText("""{"flights":[]}""")
   }
   get("/flights/{id}") { call ->
      val id = call.parameters["id"]!!
      call.respondText("""{"id":"$id"}""")
   }
   post("/orders") { call ->
      val body = call.receiveText()
      call.respondText(body, status = HttpStatusCode.Created)
   }
   put("/orders/{id}") { call -> /* ... */ }
   delete("/orders/{id}") { call -> /* ... */ }
}
```

Signature:
```kotlin
fun http(port: Int = 0, componentName: String = "http", dsl: HttpApiBuilder.(KLogger) -> Unit)
```

Inside the block you have: `get`, `post`, `put`, `delete`. Each takes a path and a suspend lambda with a `PipelineContext` receiver. The single argument is the `ApplicationCall` (named `call` by convention).

**Handler patterns:**
- `call.parameters["id"]` — path params (e.g. `/users/{id}`).
- `call.request.queryParameters["q"]` — query params.
- `call.request.headers.toMap()` — headers.
- `call.receiveText()` — raw request body as string.
- `call.respondText(body)` — 200 OK text response.
- `call.respondText(body, status = HttpStatusCode.NotFound)` — custom status.
- `call.respondText(body, contentType = ContentType.parse("application/json"))`.
- `call.respond(HttpStatusCode.NoContent)` — empty response.

**Port:** default `0` (random free port) — Orbital discovers the port from the returned `ComponentInfo`. Only pin a port if the stack instructions explicitly need it.

**JSON:** use Jackson explicitly — there is NO automatic JSON serialization:
```kotlin
val mapper = jacksonObjectMapper()
call.respondText(mapper.writeValueAsString(myMap), ContentType.parse("application/json"))
```

To parse a request body as a typed class:
```kotlin
val request = jacksonObjectMapper().readValue<MyRequest>(call.receiveText())
```

### 5.2 `kafka { ... }` — Kafka broker with producers

```kotlin
kafka {
   producer("500ms".duration(), "orders") {
      jsonMessage {
         mapOf("orderId" to "ORD-1", "amount" to 42)
      }
   }
}
```

Signature:
```kotlin
fun kafka(imageName: String = "confluentinc/cp-kafka:6.2.2",
          componentName: ComponentName = "kafka",
          dsl: KafkaBuilder.(KLogger) -> Unit)
```

**`producer(...)` signature:**
```kotlin
fun producer(frequency: Duration,
             topic: String,
             partitions: Int = 1,
             keySerializer: MessageSerializer = MessageSerializer.String,
             valueSerializer: MessageSerializer = MessageSerializer.String,
             init: ProducerBuilder.() -> Unit)
```

Frequency — prefer `"500ms".duration()` or `1.milliseconds` / `2.seconds` (using `kotlin.time.Duration`). Both appear in real stacks.

**Message generators** — pick ONE inside the producer block:

| Function | Output | Use when |
|---|---|---|
| `message { … }` | Raw value from the lambda (`String`, `ByteArray`, etc.) — sent as-is | You are producing plain text/XML/already-encoded bytes |
| `jsonMessage { … }` | `Map`/object → JSON string | Most JSON producers |
| `jsonMessages { … }` | `List<Map>` → one Kafka message per list element | You want a batch each tick (see state-sharing pattern below) |
| `avroMessage(schema) { … }` | `Map`/JSON string → Avro binary. Use for standard JSON. | Avro output, standard JSON input |
| `avroJsonMessage(schema) { … }` | Avro-specific JSON string → Avro binary. Avro-JSON uses `{"string":"x"}` union nesting. | Avro output, Avro-JSON input |
| `protoMessage(schema, "TypeName") { … }` | `Map` → protobuf binary | Protobuf output, one message per tick |
| `protoMessages(schema, "TypeName") { … }` | `List<Map>` → protobuf binary, one per list element | Protobuf batches |

**Critical: for Avro and Protobuf you MUST set `valueSerializer = MessageSerializer.ByteArray`:**
```kotlin
producer("500ms".duration(), "orders", valueSerializer = MessageSerializer.ByteArray) {
   protoMessage(schema, "com.example.Order") {
      mapOf("orderId" to "ORD-1")
   }
}
```

**Schema helpers:**
```kotlin
val avro = avroSchema(""" { "type":"record", "name":"...", ... } """)

// Single file:
val proto = protobufSchema(""" syntax = "proto3"; message Foo { string x = 1; } """)

// Multiple files (for imports across .proto files):
val proto = protobufSchema(mapOf(
   "taxi/dataType.proto" to """syntax = "proto3"; ...""",
   "orders.proto"        to """syntax = "proto3"; import "taxi/dataType.proto"; ..."""
))
```

**State across producers** — both producers run in the same JVM, so share state with thread-safe containers:
```kotlin
kafka {
   val counter = java.util.concurrent.atomic.AtomicInteger(0)
   val pending = java.util.concurrent.ConcurrentHashMap<String, Order>()

   producer("1s".duration(), "orders") {
      jsonMessage {
         val id = "ORD-${counter.incrementAndGet()}"
         // ... add to pending
      }
   }
   producer("500ms".duration(), "updates") {
      jsonMessages {
         // drain from pending, emit updates
      }
   }
}
```

### 5.3 `postgres { ... }` and `mysql { ... }` — SQL databases

```kotlin
postgres(componentName = "products-db") {
   table("suppliers", """
      CREATE TABLE suppliers (
         supplier_id VARCHAR PRIMARY KEY,
         name VARCHAR NOT NULL,
         tier VARCHAR NOT NULL
      )
   """.trimIndent(),
   data = listOf(
      mapOf("supplier_id" to "SUP-1", "name" to "Acme", "tier" to "GOLD"),
      mapOf("supplier_id" to "SUP-2", "name" to "Beta", "tier" to "SILVER")
   ))
}
```

Signatures:
```kotlin
fun postgres(imageName: String = "postgres:13",
             databaseName: String = "testDb",
             componentName: ComponentName = "postgres",
             dsl: DatabaseBuilder.(KLogger) -> Unit)

fun mysql(imageName: String = "mysql:9",
          databaseName: String = "testDb",
          componentName: ComponentName = "mysql",
          dsl: DatabaseBuilder.(KLogger) -> Unit)
```

Inside the block:
```kotlin
fun table(name: String, ddl: String, data: List<Map<String, Any>> = emptyList())
fun table(name: String, ddl: String, vararg data: Map<String, Any>)
```

**Rules:**
- `ddl` is a raw SQL `CREATE TABLE ...` string. Use `.trimIndent()` to keep it readable.
- Column names in the `data` maps must match the DDL columns exactly (case-sensitive on Postgres if you quote them; otherwise lowercase).
- Values can be `String`, `Int`, `Long`, `Boolean`, `BigDecimal`, `java.time.Instant`, `java.time.LocalDate`, `java.util.UUID`, etc. — JOOQ handles the conversion.
- An **empty table** is fine (use `data = emptyList()`) — Orbital may write into it later.
- To get a second database, give it a distinct `componentName` AND (usually) a distinct `databaseName`:
  ```kotlin
  postgres(componentName = "orders-db", databaseName = "orders")   { … }
  postgres(componentName = "products-db", databaseName = "products") { … }
  ```

### 5.4 `mongo(...) { ... }` — MongoDB

```kotlin
mongo(databaseName = "customers") {
   collection("tier", data = listOf(
      mapOf("_id" to "gold",   "description" to "Gold Tier"),
      mapOf("_id" to "silver", "description" to "Silver Tier")
   ))
   collection("order-updates")  // empty collection — Orbital writes to it
}
```

Signature:
```kotlin
fun mongo(imageName: String = "mongo:7.0.16",
          databaseName: String,                 // REQUIRED — no default
          componentName: ComponentName = "mongo",
          dsl: MongoBuilder.(KLogger) -> Unit)

fun collection(name: String, data: List<Map<String,Any>> = emptyList())
```

`databaseName` is mandatory. Use the Mongo field name `_id` in seed data if you want to pin document IDs.

### 5.5 `s3 { ... }` — S3 (LocalStack-backed)

```kotlin
s3 {
   bucket("tickets") {
      file("sales.csv", """Title,FilmId,Tickets
A New Hope,XHXCO,249
Empire Strikes Back,LXILO,189
""")
   }
   bucket("reports") {
      file("/absolute/path/to/local.csv")          // read from disk
      file("large.csv", generateSequence())        // Sequence<String> for large/streamed files
   }
}
```

Signature:
```kotlin
fun s3(imageName: String = "localstack/localstack:latest",
       componentName: String = "s3",
       dsl: S3Builder.(KLogger) -> Unit)
```

Inside a `bucket(name)` block:
```kotlin
fun file(name: String, content: String)         // inline content
fun file(name: String, content: Sequence<String>) // streaming — for large files
fun file(path: String)                           // read file from local disk; uses the filename as the S3 key
```

Use the `Sequence<String>` form for files larger than a few MB (the seeder uses S3 multipart upload — minimum part size is 5 MB, which is already handled by default).

### 5.6 `hazelcast { ... }` — Hazelcast

Almost always empty-configured. Orbital connects and uses it as a cache.

```kotlin
hazelcast { }
```

Signature:
```kotlin
fun hazelcast(imageName: String = "hazelcast/hazelcast:5",
              componentName: ComponentName = "hazelcast",
              dsl: HazelcastBuilder.(KLogger) -> Unit)
```

### 5.7 `taxiPublisher(...) { ... }` — publish taxi/proto/avro schemas to a running Orbital

Registers schema sources against a running Orbital instance on startup. Use this when the stack needs to push protobuf/avro/taxi sources into Orbital alongside the runtime infra (e.g. so Orbital can decode messages).

```kotlin
taxiPublisher(url = "http://localhost:9022", packageUri = "com.foo/publishing-test/1.0.0") {
   taxi("foo.taxi") {
      """
      type CustomerId inherits String
      """.trimIndent()
   }
   additionalSource("@orbital/protobuf", "orders.proto" to protoSourceString)
   // or with a builder lambda:
   additionalSource("@orbital/config", "connections.conf") {
      """ connection settings here """.trimIndent()
   }
}
```

Use this sparingly — most stacks do NOT need it. Only include when the user asks for programmatic schema publishing.

---

## 6. Writing style — patterns that work

### 6.1 Define seed data at the top, reference it everywhere

Putting test data at the top (or as top-level `val`s) lets multiple components share it:

```kotlin
data class Supplier(val id: String, val name: String, val tier: String)
val suppliers = listOf(
   Supplier("SUP-1", "Acme", "GOLD"),
   Supplier("SUP-2", "Beta", "SILVER")
)

stack {
   postgres {
      table("suppliers", "CREATE TABLE suppliers(id VARCHAR, name VARCHAR, tier VARCHAR)",
         data = suppliers.map { mapOf("id" to it.id, "name" to it.name, "tier" to it.tier) })
   }
   http {
      val mapper = jacksonObjectMapper()
      get("/suppliers/{id}") { call ->
         val s = suppliers.first { it.id == call.parameters["id"] }
         call.respondText(mapper.writeValueAsString(s))
      }
   }
}
```

### 6.2 One `http { }` block with multiple routes — not one per route

Group all HTTP endpoints into a single `http { }` block. Multiple `http { }` blocks would stand up multiple servers.

### 6.3 Use `jacksonObjectMapper()` for JSON everywhere

Create a mapper once per `http { }` (or reuse a script-level one). There is no automatic JSON content negotiation.

### 6.4 Use `componentName` when you have duplicates

If you have two Postgres databases, two S3 instances, etc., each MUST have a distinct `componentName`. Without it, the default name collides and the second component shadows the first.

### 6.5 Jackson + `java.time.Instant` gotcha

Jackson inside the Nebula runtime occasionally fails to serialise `Instant` cleanly. Convert to string at the boundary:
```kotlin
"orderTime" to Instant.now().toString()
```

### 6.6 Logging

`kafka { logger -> … }`, `http { logger -> … }`, etc. all receive a `KLogger` as the block receiver argument. You can use it, or just use `println` (both end up in the Nebula log stream).

### 6.7 Don't invoke `.start()` or wire `StackRunner` yourself

A Nebula script's only job is to declare the `stack { }`. The Nebula runtime (inside the CLI or Orbital) runs and manages it. Never call `.start()`, `StackRunner(...)`, `shutDownAll()`, etc. inside a stack script — those are for the host.

---

## 7. A complete minimal stack (reference)

```kotlin
// orbital/nebula/stack.nebula.kts
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.orbitalhq.nebula.utils.duration
import io.ktor.http.*

data class Film(val id: Int, val title: String)
val films = listOf(Film(1, "A New Hope"), Film(2, "Empire Strikes Back"))

stack {
   val mapper = jacksonObjectMapper()

   postgres(databaseName = "films") {
      table("film", """
         CREATE TABLE film (
            id INT PRIMARY KEY,
            title VARCHAR NOT NULL
         )
         """.trimIndent(),
         data = films.map { mapOf("id" to it.id, "title" to it.title) }
      )
   }

   http {
      get("/films") { call ->
         call.respondText(mapper.writeValueAsString(films),
            ContentType.parse("application/json"))
      }
      get("/films/{id}") { call ->
         val id = call.parameters["id"]!!.toInt()
         val film = films.firstOrNull { it.id == id }
         if (film == null) {
            call.respondText("""{"error":"not found"}""", status = HttpStatusCode.NotFound)
         } else {
            call.respondText(mapper.writeValueAsString(film),
               ContentType.parse("application/json"))
         }
      }
   }

   kafka {
      producer("1s".duration(), "film-announcements") {
         jsonMessage {
            val f = films.random()
            mapOf("filmId" to f.id, "title" to f.title, "event" to "rerelease")
         }
      }
   }
}
```

---

## 8. Quick checklist before returning a stack

- [ ] File is named `stack.nebula.kts` (unless user specified otherwise) and placed at `orbital/nebula/stack.nebula.kts`.
- [ ] `taxi.conf` contains `"@orbital/nebula" : "orbital/nebula/*.nebula.kts"` under `additionalSources`.
- [ ] Exactly one top-level `stack { }` block.
- [ ] No re-imports of `stack`, `Random`, `io.ktor.server.request.*`, `io.ktor.server.response.*`.
- [ ] Duplicate component types (two Postgres, two S3, etc.) each have distinct `componentName`s.
- [ ] Avro/Protobuf producers set `valueSerializer = MessageSerializer.ByteArray`.
- [ ] Mongo call passes `databaseName = "..."` — it has no default.
- [ ] No `.start()`, `StackRunner`, or lifecycle calls in the script.
- [ ] JSON responses use `jacksonObjectMapper().writeValueAsString(...)` — no Ktor auto-content-negotiation is configured.
- [ ] DDL strings use `.trimIndent()` and column names match the seed data map keys.
