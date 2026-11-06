package luna.mcp.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class BroadcastResult(
    val status: String,
    @SerialName("delivered_channels")
    val deliveredChannels: Int,
) {
    fun toJsonString(): String = Json.encodeToString(serializer(), this)
}

@Serializable
data class DiscordWebhookPayload(
    val content: String,
    val username: String,
    @SerialName("avatar_url")
    val avatarUrl: String,
)

class DiscordWebhookBroadcaster(
    private val repository: McpWebhookRepository,
    private val httpClient: HttpClient = createDefaultHttpClient(),
) {
    private val logger = LoggerFactory.getLogger(DiscordWebhookBroadcaster::class.java)

    suspend fun broadcast(message: String): BroadcastResult {
        val webhooks = repository.getAllWebhooks()
        if (webhooks.isEmpty()) {
            logger.warn("Discord webhook broadcast requested, but no active webhooks are registered in database")
            return BroadcastResult(status = "no_channels_registered", deliveredChannels = 0)
        }

        val chunks = DiscordMessageChunker.chunk(message)
        var delivered = 0

        for (webhook in webhooks) {
            val success =
                runCatching {
                    for (chunk in chunks) {
                        val response =
                            httpClient.post(webhook.webhookUrl) {
                                contentType(ContentType.Application.Json)
                                setBody(
                                    DiscordWebhookPayload(
                                        content = chunk,
                                        username = GEMINI_USERNAME,
                                        avatarUrl = GEMINI_AVATAR_URL,
                                    ),
                                )
                            }
                        if (!response.status.isSuccess()) {
                            error("Webhook dispatch returned HTTP ${response.status}")
                        }
                    }
                    true
                }.onFailure { e ->
                    logger.error("Failed to dispatch message to webhook URL for channel {}: {}", webhook.channelId, e.message)
                }.getOrDefault(false)

            if (success) {
                delivered++
            }
        }

        logger.info("Discord webhook broadcast delivered to {}/{} channels", delivered, webhooks.size)
        return BroadcastResult(status = "success", deliveredChannels = delivered)
    }

    companion object {
        const val GEMINI_USERNAME = "Gemini"
        const val GEMINI_AVATAR_URL =
            "https://www.gstatic.com/lamda/images/gemini_sparkle_v002_d4735304ff6292a690345.svg"

        fun createDefaultHttpClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            encodeDefaults = true
                        },
                    )
                }
            }
    }
}
