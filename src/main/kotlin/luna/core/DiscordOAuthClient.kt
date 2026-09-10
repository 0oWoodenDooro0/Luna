package luna.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class DiscordUserProfile(
    val id: String,
    val username: String,
    val globalName: String? = null,
) {
    val displayName: String get() = globalName ?: username
}

interface DiscordOAuthClient {
    fun getAuthorizationUrl(redirectUri: String): String

    suspend fun exchangeCode(
        code: String,
        redirectUri: String,
    ): DiscordUserProfile
}

class HttpDiscordOAuthClient(
    private val clientIdProvider: () -> String = {
        System.getenv("DISCORD_CLIENT_ID") ?: System.getenv("DISCORD_APP_ID") ?: ""
    },
    private val clientSecretProvider: () -> String = {
        System.getenv("DISCORD_CLIENT_SECRET") ?: ""
    },
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) : DiscordOAuthClient {
    override fun getAuthorizationUrl(redirectUri: String): String {
        val clientId = clientIdProvider()
        require(clientId.isNotBlank()) {
            "伺服器未設定 DISCORD_CLIENT_ID 環境變數，無法啟用 Discord OAuth2 登入。"
        }
        val encodedRedirect = URLEncoder.encode(redirectUri, "UTF-8")
        return "https://discord.com/oauth2/authorize?client_id=$clientId&redirect_uri=$encodedRedirect&response_type=code&scope=identify"
    }

    override suspend fun exchangeCode(
        code: String,
        redirectUri: String,
    ): DiscordUserProfile {
        val clientId = clientIdProvider()
        val clientSecret = clientSecretProvider()
        require(clientId.isNotBlank()) { "未設定 DISCORD_CLIENT_ID" }
        require(clientSecret.isNotBlank()) { "未設定 DISCORD_CLIENT_SECRET" }

        val encodedRedirect = URLEncoder.encode(redirectUri, "UTF-8")
        val tokenRequestBody =
            "client_id=$clientId&client_secret=$clientSecret&grant_type=authorization_code&code=$code&redirect_uri=$encodedRedirect"

        val tokenReq =
            HttpRequest
                .newBuilder()
                .uri(URI.create("https://discord.com/api/v10/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(tokenRequestBody))
                .build()

        val tokenRes = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString())
        if (tokenRes.statusCode() != 200) {
            throw IllegalStateException("Discord token exchange error (HTTP ${tokenRes.statusCode()})")
        }

        val tokenJson = Json.parseToJsonElement(tokenRes.body()).jsonObject
        val accessToken =
            tokenJson["access_token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("No access_token returned from Discord")

        val userReq =
            HttpRequest
                .newBuilder()
                .uri(URI.create("https://discord.com/api/v10/users/@me"))
                .header("Authorization", "Bearer $accessToken")
                .GET()
                .build()

        val userRes = httpClient.send(userReq, HttpResponse.BodyHandlers.ofString())
        if (userRes.statusCode() != 200) {
            throw IllegalStateException("Discord user fetch error (HTTP ${userRes.statusCode()})")
        }

        val userJson = Json.parseToJsonElement(userRes.body()).jsonObject
        val discordId =
            userJson["id"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("No user id returned from Discord")
        val globalName = userJson["global_name"]?.jsonPrimitive?.content
        val username = userJson["username"]?.jsonPrimitive?.content ?: "DiscordUser"

        return DiscordUserProfile(
            id = discordId,
            username = username,
            globalName = globalName,
        )
    }
}
