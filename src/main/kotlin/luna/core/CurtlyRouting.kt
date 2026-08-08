package luna.core

import com.github._0owoodendooro0.curtly.CurtlyService
import com.github._0owoodendooro0.curtly.CurtlyWebPage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

@Serializable
data class UserSession(
    val userId: String,
    val username: String,
)

fun Routing.curtlyRouting(
    curtlyService: CurtlyService,
    userStorage: UserStorage,
    baseUrl: String = "http://localhost:8080/s/",
) {
    // ----------------------------------------------------
    // Web Pages
    // ----------------------------------------------------
    get("/login") {
        val session = call.sessions.get<UserSession>()
        if (session != null) {
            call.respondRedirect("/dashboard")
            return@get
        }
        call.respondText(LunaWebPages.getLoginPageHtml(), ContentType.Text.Html)
    }

    get("/dashboard") {
        val session = call.sessions.get<UserSession>()
        if (session == null) {
            call.respondRedirect("/login")
            return@get
        }
        call.respondText(LunaWebPages.getDashboardPageHtml(session.username), ContentType.Text.Html)
    }

    get("/shorten") {
        call.respondText(LunaWebPages.getPublicShortenPageHtml(), ContentType.Text.Html)
    }

    get("/s") {
        call.respondText(LunaWebPages.getPublicShortenPageHtml(), ContentType.Text.Html)
    }

    // ----------------------------------------------------
    // Short URL Redirection
    // ----------------------------------------------------
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

    // ----------------------------------------------------
    // Discord OAuth2 Routes
    // ----------------------------------------------------
    get("/api/auth/discord/login") {
        val clientId = System.getenv("DISCORD_CLIENT_ID") ?: System.getenv("DISCORD_APP_ID") ?: ""
        if (clientId.isBlank()) {
            call.respondText(
                "❌ 伺服器未設定 DISCORD_CLIENT_ID 環境變數，無法啟用 Discord OAuth2 登入。",
                ContentType.Text.Plain,
                HttpStatusCode.InternalServerError,
            )
            return@get
        }

        val redirectUri = call.resolveDiscordRedirectUri(baseUrl)
        val encodedRedirect = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        val discordAuthUrl =
            "https://discord.com/oauth2/authorize?client_id=$clientId&redirect_uri=$encodedRedirect&response_type=code&scope=identify"

        call.respondRedirect(discordAuthUrl)
    }

    get("/api/auth/discord/callback") {
        val code = call.parameters["code"]
        if (code.isNullOrBlank()) {
            call.respondRedirect("/login?error=discord_cancel")
            return@get
        }

        try {
            val clientId = System.getenv("DISCORD_CLIENT_ID") ?: System.getenv("DISCORD_APP_ID") ?: ""
            val clientSecret = System.getenv("DISCORD_CLIENT_SECRET") ?: ""
            val redirectUri = call.resolveDiscordRedirectUri(baseUrl)

            val httpClient =
                java.net.http.HttpClient
                    .newHttpClient()

            val encodedRedirect = java.net.URLEncoder.encode(redirectUri, "UTF-8")
            val tokenRequestBody =
                "client_id=$clientId&client_secret=$clientSecret&grant_type=authorization_code&code=$code&redirect_uri=$encodedRedirect"

            val tokenReq =
                java.net.http.HttpRequest
                    .newBuilder()
                    .uri(java.net.URI.create("https://discord.com/api/v10/oauth2/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        java.net.http.HttpRequest.BodyPublishers
                            .ofString(tokenRequestBody),
                    ).build()

            val tokenRes =
                httpClient.send(
                    tokenReq,
                    java.net.http.HttpResponse.BodyHandlers
                        .ofString(),
                )
            if (tokenRes.statusCode() != 200) {
                call.respondRedirect("/login?error=discord_token_error")
                return@get
            }

            val tokenJson = Json.parseToJsonElement(tokenRes.body()).jsonObject
            val accessToken =
                tokenJson["access_token"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("No access_token returned")

            val userReq =
                java.net.http.HttpRequest
                    .newBuilder()
                    .uri(java.net.URI.create("https://discord.com/api/v10/users/@me"))
                    .header("Authorization", "Bearer $accessToken")
                    .GET()
                    .build()

            val userRes =
                httpClient.send(
                    userReq,
                    java.net.http.HttpResponse.BodyHandlers
                        .ofString(),
                )
            if (userRes.statusCode() != 200) {
                call.respondRedirect("/login?error=discord_user_error")
                return@get
            }

            val userJson = Json.parseToJsonElement(userRes.body()).jsonObject
            val discordId =
                userJson["id"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("No user id returned")
            val globalName = userJson["global_name"]?.jsonPrimitive?.content
            val username = userJson["username"]?.jsonPrimitive?.content ?: "DiscordUser"
            val displayName = globalName ?: username

            val user = userStorage.upsertDiscordUser(discordUserId = discordId, username = displayName)

            call.sessions.set(UserSession(user.id, user.username))
            call.respondRedirect("/dashboard")
        } catch (e: Exception) {
            call.respondRedirect("/login?error=${java.net.URLEncoder.encode(e.message ?: "unknown", "UTF-8")}")
        }
    }

    // ----------------------------------------------------
    // User Authentication APIs
    // ----------------------------------------------------
    post("/api/auth/register") {
        try {
            val body = Json.parseToJsonElement(call.receiveText()).jsonObject
            val username = body["username"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("使用者名稱為必填")
            val password = body["password"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("密碼為必填")

            val user = userStorage.register(username, password)
            call.sessions.set(UserSession(user.id, user.username))
            call.respondText(
                """{"message":"註冊成功","user":{"id":"${user.id}","username":"${user.username}"}}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            val msg = (e.message ?: "註冊失敗").replace("\"", "\\\"")
            call.respondText("""{"error":"$msg"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        }
    }

    post("/api/auth/login") {
        try {
            val body = Json.parseToJsonElement(call.receiveText()).jsonObject
            val username = body["username"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("使用者名稱為必填")
            val password = body["password"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("密碼為必填")

            val user =
                userStorage.authenticate(username, password)
                    ?: throw IllegalArgumentException("使用者名稱或密碼錯誤")

            call.sessions.set(UserSession(user.id, user.username))
            call.respondText(
                """{"message":"登入成功","user":{"id":"${user.id}","username":"${user.username}"}}""",
                ContentType.Application.Json,
            )
        } catch (e: Exception) {
            val msg = (e.message ?: "登入失敗").replace("\"", "\\\"")
            call.respondText("""{"error":"$msg"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        }
    }

    post("/api/auth/logout") {
        call.sessions.clear<UserSession>()
        call.respondText("""{"message":"已登出"}""", ContentType.Application.Json)
    }

    get("/api/auth/me") {
        val session = call.sessions.get<UserSession>()
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, """{"error":"未登入"}""")
            return@get
        }
        call.respondText("""{"id":"${session.userId}","username":"${session.username}"}""", ContentType.Application.Json)
    }

    // ----------------------------------------------------
    // Link Creation API (Binds ownerId if user is logged in)
    // ----------------------------------------------------
    post("/api/shorten") {
        try {
            val text = call.receiveText()
            val json = Json.parseToJsonElement(text).jsonObject
            val longUrl =
                json["longUrl"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Destination URL is required")
            val customKey =
                json["customKey"]
                    ?.takeIf { it !is JsonNull }
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it != "null" }
            val expiresInSeconds = json["expiresInSeconds"]?.jsonPrimitive?.longOrNull
            val maxClicks = json["maxClicks"]?.jsonPrimitive?.intOrNull

            val session = call.sessions.get<UserSession>()
            val ownerId = session?.userId

            val shortUrl =
                curtlyService.shorten(
                    longUrl = longUrl,
                    customKey = customKey,
                    expiresInSeconds = expiresInSeconds,
                    maxClicks = maxClicks,
                    ownerId = ownerId,
                )

            call.respondText("""{"shortUrl":"$shortUrl"}""", ContentType.Application.Json)
        } catch (e: Exception) {
            val msg = (e.message ?: "Error shortening URL").replace("\"", "\\\"")
            call.respondText("""{"error":"$msg"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        }
    }

    // ----------------------------------------------------
    // User Links & Analytics APIs (Requires Login)
    // ----------------------------------------------------
    get("/api/user/urls") {
        val session = call.sessions.get<UserSession>()
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, """{"error":"未登入"}""")
            return@get
        }

        val entries = curtlyService.getByOwnerId(session.userId)
        val jsonArray =
            buildJsonArray {
                entries.forEach { (key, entry) ->
                    add(
                        buildJsonObject {
                            put("key", key)
                            val cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                            put("shortUrl", "$cleanBaseUrl$key")
                            put("longUrl", entry.longUrl)
                            put("clickCount", entry.clickCount)
                            entry.expiresAt?.let { put("expiresAt", it) }
                            entry.maxClicks?.let { put("maxClicks", it) }
                            entry.ownerId?.let { put("ownerId", it) }
                        },
                    )
                }
            }

        call.respondText(jsonArray.toString(), ContentType.Application.Json)
    }

    get("/api/user/urls/{key}/stats") {
        val session = call.sessions.get<UserSession>()
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, """{"error":"未登入"}""")
            return@get
        }

        val key = call.parameters["key"]
        if (key.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, """{"error":"缺少短網址 Key"}""")
            return@get
        }

        val entry = curtlyService.getEntry(key)
        if (entry == null || entry.ownerId != session.userId) {
            call.respond(HttpStatusCode.Forbidden, """{"error":"權限不足或短網址不存在"}""")
            return@get
        }

        val analytics = curtlyService.getAnalytics(key)
        if (analytics == null) {
            call.respond(HttpStatusCode.NotFound, """{"error":"查無分析數據"}""")
            return@get
        }
        val json =
            buildJsonObject {
                put("key", analytics.key)
                put("totalClicks", analytics.totalClicks)
                put(
                    "clicksByDate",
                    buildJsonObject {
                        analytics.clicksByDate.forEach { (d, c) -> put(d, c) }
                    },
                )
                put(
                    "topReferers",
                    buildJsonObject {
                        analytics.topReferers.forEach { (r, c) -> put(r, c) }
                    },
                )
                put(
                    "topUserAgents",
                    buildJsonObject {
                        analytics.topUserAgents.forEach { (ua, c) -> put(ua, c) }
                    },
                )
                put(
                    "topIps",
                    buildJsonObject {
                        analytics.topIps.forEach { (ip, c) -> put(ip, c) }
                    },
                )
                put(
                    "logs",
                    buildJsonArray {
                        analytics.logs.forEach { log ->
                            add(
                                buildJsonObject {
                                    put("id", log.id)
                                    put("timestamp", log.timestamp)
                                    log.ip?.let { put("ip", it) }
                                    log.userAgent?.let { put("userAgent", it) }
                                    log.referer?.let { put("referer", it) }
                                },
                            )
                        }
                    },
                )
            }

        call.respondText(json.toString(), ContentType.Application.Json)
    }

    delete("/api/user/urls/{key}") {
        val session = call.sessions.get<UserSession>()
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, """{"error":"未登入"}""")
            return@delete
        }

        val key = call.parameters["key"]
        if (key.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, """{"error":"缺少短網址 Key"}""")
            return@delete
        }

        val entry = curtlyService.getEntry(key)
        if (entry == null || entry.ownerId != session.userId) {
            call.respond(HttpStatusCode.Forbidden, """{"error":"權限不足或短網址不存在"}""")
            return@delete
        }

        curtlyService.delete(key)
        call.respondText("""{"message":"已刪除短網址"}""", ContentType.Application.Json)
    }
}

private fun io.ktor.server.application.ApplicationCall.resolveDiscordRedirectUri(baseUrl: String): String {
    val envRedirect = System.getenv("DISCORD_REDIRECT_URI")
    if (!envRedirect.isNullOrBlank()) return envRedirect

    val proto = request.headers["X-Forwarded-Proto"] ?: request.origin.scheme
    val host = request.headers["X-Forwarded-Host"] ?: request.headers["Host"] ?: request.origin.serverHost

    return if (host.isNotBlank() && !host.contains("localhost")) {
        "$proto://$host/api/auth/discord/callback"
    } else {
        val appBaseUrl =
            if (baseUrl.endsWith("/s/")) {
                baseUrl.substringBefore("/s/")
            } else if (baseUrl.endsWith("/")) {
                baseUrl.dropLast(1)
            } else {
                baseUrl
            }
        "$appBaseUrl/api/auth/discord/callback"
    }
}
