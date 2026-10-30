# Curtly

A lightweight Kotlin library for URL shortening, custom key generation, expiration handling, and click-limit tracking.

## Usage

```kotlin
import com.github._0owoodendooro0.curtly.*
import java.io.File

// In-Memory Storage
val storage = InMemoryUrlStorage()

// Or Exposed SQL Storage (decoupled database connection)
// val db = Database.connect("jdbc:h2:mem:curtly;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
// val storage = ExposedUrlStorage(database = db)

val curtly = CurtlyService(
    storage = storage,
    baseUrl = "https://short.link/"
)

// Shorten a URL
val shortUrl = curtly.shorten(
    longUrl = "https://example.com/very/long/url",
    customKey = "my-custom-key",
    expiresInSeconds = 3600, // Optional expiration
    maxClicks = 10           // Optional click limit
)

println(shortUrl) // https://short.link/my-custom-key

// Resolve a key back to the original long URL
val originalUrl = curtly.resolve("my-custom-key")
println(originalUrl) // https://example.com/very/long/url
```

## Features

- **URL Normalization**: Ensures URLs start with `http://` or `https://`.
- **Custom Keys**: Supports custom key aliases or auto-generated random keys.
- **Expiration Support**: Expiration by duration (`expiresInSeconds`) or exact timestamp (`expiresAt`).
- **Click Limit**: Limit maximum allowed clicks (`maxClicks`).
- **Click Analytics & Audit Mode**: Track detailed click traces (timestamps, IP, User-Agent, Referer) for reporting and analytics.
- **Flexible Storage**: Pluggable storage system (`InMemoryUrlStorage` and SQL-backed `ExposedUrlStorage`).
- **Built-in Web Page**: Lightweight UI generated via Kotlin HTML DSL (`CurtlyWebPage.getHtml()`).

## Click Analytics & Audit Mode

`Curtly` supports detailed click audit logging and analytics computation alongside standard click counts.

### 1. Enable Audit Mode or Record Metadata on Resolve

```kotlin
// Enable global audit mode when initializing service
val curtly = CurtlyService(
    storage = storage,
    enableAuditMode = true
)

// Resolve URL and pass visitor metadata (IP, User-Agent, Referer)
val longUrl = curtly.resolve(
    key = "my-custom-key",
    ip = "203.0.113.195",
    userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
    referer = "https://google.com"
)
```

### 2. Query Click Analytics & Reports

```kotlin
// Get aggregated analytics report for a short URL key
val analytics: ClickAnalytics? = curtly.getAnalytics("my-custom-key")

println(analytics?.totalClicks)       // Total click count (e.g. 42)
println(analytics?.auditLogsCount)    // Total audit traces logged
println(analytics?.clicksByDate)      // e.g. {"2026-07-23": 15}
println(analytics?.topUserAgents)     // Map of User-Agents to count
println(analytics?.topReferers)      // Map of Referer URLs to count
println(analytics?.topIps)           // Map of IP addresses to count

// Or fetch raw click trace logs
val logs: List<ClickLog> = curtly.getClickLogs("my-custom-key")
for (log in logs) {
    println("${log.timestamp}: IP=${log.ip}, UA=${log.userAgent}, Ref=${log.referer}")
}
```

## Built-in Web Page (Ktor Integration)

`Curtly` includes a default URL creation webpage (`CurtlyWebPage`) generated using **Kotlin HTML DSL**. The library does not bundle an embedded HTTP server, making it easy to serve the UI within your Ktor server application.

### 1. Serve the Built-in Web Page

Use `CurtlyWebPage.getHtml()` inside a Ktor route to serve the URL generator webpage:

```kotlin
import com.github._0owoodendooro0.curtly.CurtlyWebPage
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

routing {
    // Serve the built-in webpage
    get("/curtly") {
        call.respondText(
            text = CurtlyWebPage.getHtml(apiEndpoint = "/api/shorten"),
            contentType = ContentType.Text.Html
        )
    }
}
```

### 2. Redirect with Click Audit Tracing

```kotlin
get("/{key}") {
    val key = call.parameters["key"] ?: return@get call.respond(HttpStatusCode.BadRequest)
    val ip = call.request.origin.remoteHost
    val userAgent = call.request.headers["User-Agent"]
    val referer = call.request.headers["Referer"]

    val targetUrl = curtly.resolve(key, ip = ip, userAgent = userAgent, referer = referer)
    if (targetUrl != null) {
        call.respondRedirect(targetUrl)
    } else {
        call.respond(HttpStatusCode.NotFound, "Short link expired or not found")
    }
}
```

### 3. Handle the Shorten API Endpoint

The webpage sends a `POST` request to `apiEndpoint` (defaults to `/api/shorten`) with the following JSON structure:

```json
{
  "longUrl": "https://example.com/very/long/url",
  "customKey": "my-custom-key",
  "expiresInSeconds": 3600,
  "maxClicks": 10
}
```

Example Ktor handler connecting the webpage to `CurtlyService`:

```kotlin
import com.github._0owoodendooro0.curtly.CurtlyService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ShortenRequest(
    val longUrl: String,
    val customKey: String? = null,
    val expiresInSeconds: Long? = null,
    val maxClicks: Int? = null
)

@Serializable
data class ShortenResponse(val shortUrl: String)

fun Route.curtlyRoutes(curtly: CurtlyService) {
    post("/api/shorten") {
        val req = call.receive<ShortenRequest>()
        try {
            val shortUrl = curtly.shorten(
                longUrl = req.longUrl,
                customKey = req.customKey,
                expiresInSeconds = req.expiresInSeconds,
                maxClicks = req.maxClicks
            )
            call.respond(ShortenResponse(shortUrl = shortUrl))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        }
    }
}
```

## Building & Testing

| Task | Description |
|------|-------------|
| `./gradlew test`  | Run unit tests |
| `./gradlew build` | Build the library JAR |


