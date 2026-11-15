package dev.socialpeek.resolver.bilibili

import dev.socialpeek.exception.ParsingException
import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.model.Author
import dev.socialpeek.network.SocialPeekHttpClient
import dev.socialpeek.util.BilibiliUrlPolicy
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

internal class BilibiliApiClient(
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    companion object {
        private val logger = LoggerFactory.getLogger(BilibiliApiClient::class.java)

        val BILIBILI_HEADERS: Map<String, String> =
            mapOf(
                HttpHeaders.UserAgent to "SocialPeek/1.0 (https://github.com/0oWoodenDooro0/SocialPeek)",
                "Referer" to "https://www.bilibili.com/",
            )
    }

    suspend fun getJsonRoot(
        apiUrl: String,
        originalUrl: String,
        apiContext: String,
        client: SocialPeekHttpClient,
    ): JsonObject {
        val responseText =
            try {
                client.get(apiUrl, BILIBILI_HEADERS)
            } catch (e: Exception) {
                throw PostNotFoundException(originalUrl, e.message)
            }

        val root =
            try {
                json.parseToJsonElement(responseText).jsonObject
            } catch (e: Exception) {
                throw ParsingException(originalUrl, "Failed to parse Bilibili $apiContext JSON", e)
            }

        val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Bilibili error code $code"
            throw PostNotFoundException(originalUrl, message)
        }

        return root
    }

    suspend fun getJsonData(
        apiUrl: String,
        originalUrl: String,
        apiContext: String,
        client: SocialPeekHttpClient,
    ): JsonObject {
        val root = getJsonRoot(apiUrl, originalUrl, apiContext, client)
        return root["data"]?.jsonObject
            ?: throw ParsingException(originalUrl, "Missing 'data' field in Bilibili $apiContext response")
    }

    suspend fun getJsonDataOrNull(
        apiUrl: String,
        contextDescription: String,
        client: SocialPeekHttpClient,
    ): JsonObject? =
        try {
            val responseText = client.get(apiUrl, BILIBILI_HEADERS)
            val root = json.parseToJsonElement(responseText).jsonObject
            val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
            if (code == 0) {
                root["data"]?.jsonObject
            } else {
                logger.warn("{} returned non-zero code={}", contextDescription, code)
                null
            }
        } catch (e: Exception) {
            logger.warn("{} failed: {}", contextDescription, e.message)
            null
        }

    fun buildAuthor(
        mid: String,
        displayName: String,
        rawAvatarUrl: String?,
    ): Author =
        Author(
            id = mid,
            username = mid,
            displayName = displayName,
            avatarUrl = rawAvatarUrl?.takeIf { it.isNotBlank() }?.let(BilibiliUrlPolicy::upgradeToHttps),
            profileUrl = "https://space.bilibili.com/$mid/",
        )

    fun extractOwnerAuthor(
        owner: JsonObject?,
        mid: String,
        fallbackName: String,
    ): Author {
        val ownerName =
            owner
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: fallbackName
        val ownerFace = owner?.get("face")?.jsonPrimitive?.contentOrNull
        return buildAuthor(mid = mid, displayName = ownerName, rawAvatarUrl = ownerFace)
    }
}
