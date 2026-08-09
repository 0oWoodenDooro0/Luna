package luna.core

import com.github._0owoodendooro0.curtly.CurtlyService
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
import java.net.URLEncoder

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
    curtlyRouting(
        curtlyService = curtlyService,
        authService = AuthService(userStorage),
        baseUrl = baseUrl,
    )
}

fun Routing.curtlyRouting(
    curtlyService: CurtlyService,
    authService: AuthService,
    baseUrl: String = "http://localhost:8080/s/",
) {
    // Helper to resolve application base URL for OAuth redirect
    fun resolveAppBaseUrl(): String {
        return if (baseUrl.endsWith("/s/")) {
            baseUrl.substringBefore("/s/")
        } else if (baseUrl.endsWith("/")) {
            baseUrl.dropLast(1)
        } else {
            baseUrl
        }
    }

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
        try {
            val appBaseUrl = resolveAppBaseUrl()
            val redirectUri = System.getenv("DISCORD_REDIRECT_URI") ?: "$appBaseUrl/api/auth/discord/callback"
            val discordAuthUrl = authService.getDiscordLoginUrl(redirectUri)
            call.respondRedirect(discordAuthUrl)
        } catch (e: Exception) {
            call.respondText(
                "❌ ${e.message ?: "無法啟用 Discord OAuth2 登入"}",
                ContentType.Text.Plain,
                HttpStatusCode.InternalServerError,
            )
        }
    }

    get("/api/auth/discord/callback") {
        val code = call.parameters["code"]
        if (code.isNullOrBlank()) {
            call.respondRedirect("/login?error=discord_cancel")
            return@get
        }

        try {
            val appBaseUrl = resolveAppBaseUrl()
            val redirectUri = System.getenv("DISCORD_REDIRECT_URI") ?: "$appBaseUrl/api/auth/discord/callback"
            val session = authService.handleDiscordCallback(code, redirectUri)
            call.sessions.set(session)
            call.respondRedirect("/dashboard")
        } catch (e: Exception) {
            val errorMsg = URLEncoder.encode(e.message ?: "unknown", "UTF-8")
            call.respondRedirect("/login?error=$errorMsg")
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

            val session = authService.register(username, password)
            call.sessions.set(session)
            call.respondText(
                """{"message":"註冊成功","user":{"id":"${session.userId}","username":"${session.username}"}}""",
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

            val session =
                authService.authenticate(username, password)
                    ?: throw IllegalArgumentException("使用者名稱或密碼錯誤")

            call.sessions.set(session)
            call.respondText(
                """{"message":"登入成功","user":{"id":"${session.userId}","username":"${session.username}"}}""",
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
