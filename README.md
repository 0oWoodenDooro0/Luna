# Curtly

A lightweight Kotlin library for URL shortening, custom key generation, expiration handling, and click-limit tracking.

## Usage

```kotlin
import com.github._0owoodendooro0.curtly.*
import java.io.File

// In-Memory Storage
val storage = InMemoryUrlStorage()

// Or File Storage
// val storage = FileUrlStorage(File("data/urls.db"))

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
- **Flexible Storage**: Pluggable storage system (`InMemoryUrlStorage` and `FileUrlStorage`).

## Building & Testing

| Task | Description |
|------|-------------|
| `./gradlew test`  | Run unit tests |
| `./gradlew build` | Build the library JAR |

