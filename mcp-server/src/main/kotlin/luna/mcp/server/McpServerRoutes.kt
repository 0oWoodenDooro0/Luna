package luna.mcp.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.Channel
import java.util.UUID

fun Application.mcpServerModule(
    broadcaster: DiscordWebhookBroadcaster,
    sessionManager: McpSessionManager = McpSessionManager(),
) {
    install(SSE)
    install(ContentNegotiation) {
        json()
    }

    val handler = McpProtocolHandler(broadcaster)

    routing {
        sse("/mcp/sse") {
            val sessionId = UUID.randomUUID().toString()
            val channel = Channel<String>(capacity = 64)
            sessionManager.registerSession(sessionId, channel)
            try {
                send(ServerSentEvent(data = "/mcp/messages?sessionId=$sessionId", event = "endpoint"))
                for (message in channel) {
                    send(ServerSentEvent(data = message, event = "message"))
                }
            } finally {
                sessionManager.removeSession(sessionId)
            }
        }

        post("/mcp/messages") {
            val sessionId = call.request.queryParameters["sessionId"]
            val requestBody = call.receiveText()
            val responseString = handler.handle(requestBody)

            if (responseString == null) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                if (sessionId != null) {
                    sessionManager.sendToSession(sessionId, responseString)
                }
                call.respondText(responseString, ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
    }
}
