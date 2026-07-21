package com.github._0owoodendooro0

import com.github._0owoodendooro0.curtly.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ShortenRequest(
    val url: String,
    val key: String? = null,
    val expiresInSeconds: Long? = null,
    val expiresAt: Long? = null,
    val maxClicks: Int? = null
)

@Serializable
data class ShortenResponse(
    val shortUrl: String,
    val key: String,
    val longUrl: String,
    val expiresAt: Long? = null,
    val maxClicks: Int? = null,
    val clickCount: Int = 0
)

val fileStorage = FileUrlStorage(File("data/urls.db"))
lateinit var curtlyService: CurtlyService
    private set

fun Application.configureRouting() {
    val configBaseUrl = environment.config.propertyOrNull("curtly.baseUrl")?.getString()
        ?: System.getenv("CURTLY_BASE_URL")
        ?: System.getenv("BASE_URL")
        ?: System.getProperty("curtly.baseUrl")
        ?: "http://localhost:8080"

    curtlyService = CurtlyService(
        storage = fileStorage,
        baseUrl = configBaseUrl
    )

    routing {
        get("/") {
            call.respondText("Curtly URL Shortener is running! Try POSTing to /shorten with a JSON body: {\"url\": \"https://example.com\"}")
        }

        post("/shorten") {
            try {
                val request = runCatching { call.receive<ShortenRequest>() }.getOrNull()
                val url = request?.url ?: call.parameters["url"]
                val customKey = request?.key ?: call.parameters["key"]
                val expiresInSeconds = request?.expiresInSeconds ?: call.parameters["expiresInSeconds"]?.toLongOrNull()
                val expiresAt = request?.expiresAt ?: call.parameters["expiresAt"]?.toLongOrNull()
                val maxClicks = request?.maxClicks ?: call.parameters["maxClicks"]?.toIntOrNull()

                if (url.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing or empty 'url' parameter"))
                    return@post
                }

                val shortUrl = curtlyService.shorten(
                    longUrl = url,
                    customKey = customKey,
                    expiresInSeconds = expiresInSeconds,
                    expiresAt = expiresAt,
                    maxClicks = maxClicks
                )
                val generatedKey = shortUrl.substringAfterLast("/")
                val entry = curtlyService.getEntry(generatedKey)

                call.respond(
                    HttpStatusCode.Created,
                    ShortenResponse(
                        shortUrl = shortUrl,
                        key = generatedKey,
                        longUrl = url,
                        expiresAt = entry?.expiresAt,
                        maxClicks = entry?.maxClicks,
                        clickCount = entry?.clickCount ?: 0
                    )
                )
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Invalid key or URL conflict")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Internal server error")))
            }
        }

        curtlyRouting(curtlyService)
    }
}