package luna.core

import com.github._0owoodendooro0.curtly.CurtlyService
import com.github._0owoodendooro0.curtly.CurtlyWebPage
import io.ktor.http.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Routing.curtlyRouting(curtlyService: CurtlyService) {
    get("/shorten") {
        call.respondText(CurtlyWebPage.getHtml(apiEndpoint = "/api/shorten"), ContentType.Text.Html)
    }

    get("/s") {
        call.respondText(CurtlyWebPage.getHtml(apiEndpoint = "/api/shorten"), ContentType.Text.Html)
    }

    get("/s/{key}") {
        val key = call.parameters["key"]
        if (key.isNullOrBlank()) {
            call.respond(HttpStatusCode.NotFound, "URL not found")
            return@get
        }
        val ip = call.request.origin.remoteHost
        val userAgent = call.request.headers["User-Agent"]
        val referer = call.request.headers["Referer"]

        val longUrl = curtlyService.resolve(key, ip = ip, userAgent = userAgent, referer = referer)
        if (longUrl != null) {
            call.respondRedirect(longUrl)
        } else {
            call.respond(HttpStatusCode.NotFound, "URL not found or expired")
        }
    }

    post("/api/shorten") {
        try {
            val text = call.receiveText()
            val json = Json.parseToJsonElement(text).jsonObject
            val longUrl = json["longUrl"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Destination URL is required")
            val customKey = json["customKey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val expiresInSeconds = json["expiresInSeconds"]?.jsonPrimitive?.longOrNull
            val maxClicks = json["maxClicks"]?.jsonPrimitive?.intOrNull

            val shortUrl = curtlyService.shorten(
                longUrl = longUrl,
                customKey = customKey,
                expiresInSeconds = expiresInSeconds,
                maxClicks = maxClicks,
            )

            call.respondText("""{"shortUrl":"$shortUrl"}""", ContentType.Application.Json)
        } catch (e: Exception) {
            val msg = (e.message ?: "Error shortening URL").replace("\"", "\\\"")
            call.respondText("""{"error":"$msg"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        }
    }
}
