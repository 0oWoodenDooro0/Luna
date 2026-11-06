package luna.mcp.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

fun Application.mcpServerModule(
    broadcaster: DiscordWebhookBroadcaster,
    sessionManager: McpSessionManager = McpSessionManager(),
    authConfig: McpOAuthConfig = McpOAuthConfig(),
    oAuthProvider: McpOAuthProvider = McpOAuthProvider(authConfig),
) {
    install(SSE)
    install(ContentNegotiation) {
        json()
    }

    val handler = McpProtocolHandler(broadcaster)
    val baseUrl = authConfig.normalizedBaseUrl

    routing {
        get("/oauth/authorize") {
            val responseType = call.request.queryParameters["response_type"]
            val clientId = call.request.queryParameters["client_id"]
            val redirectUri = call.request.queryParameters["redirect_uri"]
            val state = call.request.queryParameters["state"]
            val codeChallenge = call.request.queryParameters["code_challenge"]
            val codeChallengeMethod = call.request.queryParameters["code_challenge_method"]

            if (responseType != "code") {
                val errorJson =
                    buildJsonObject {
                        put("error", "unsupported_response_type")
                        put("error_description", "response_type must be 'code'")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }

            if (clientId != authConfig.clientId) {
                val errorJson =
                    buildJsonObject {
                        put("error", "invalid_client")
                        put("error_description", "Unknown or invalid client_id")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }

            if (!oAuthProvider.isValidRedirectUri(redirectUri)) {
                val errorJson =
                    buildJsonObject {
                        put("error", "invalid_request")
                        put("error_description", "Unauthorized or invalid redirect_uri")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }

            val code =
                oAuthProvider.issueAuthorizationCode(
                    clientId = clientId,
                    redirectUri = redirectUri!!,
                    codeChallenge = codeChallenge,
                    codeChallengeMethod = codeChallengeMethod,
                )

            val separator = if (redirectUri.contains('?')) "&" else "?"
            val encodedCode = URLEncoder.encode(code, StandardCharsets.UTF_8.name())
            var targetUrl = "$redirectUri${separator}code=$encodedCode"
            if (!state.isNullOrEmpty()) {
                val encodedState = URLEncoder.encode(state, StandardCharsets.UTF_8.name())
                targetUrl += "&state=$encodedState"
            }

            call.respondRedirect(targetUrl, permanent = false)
        }

        post("/oauth/token") {
            // Extract Basic Auth if present
            val authHeader = call.request.headers[HttpHeaders.Authorization]
            var basicClientId: String? = null
            var basicClientSecret: String? = null
            if (authHeader != null && authHeader.startsWith("Basic ", ignoreCase = true)) {
                runCatching {
                    val base64Credentials = authHeader.substring(6).trim()
                    val decoded = String(Base64.getDecoder().decode(base64Credentials), StandardCharsets.UTF_8)
                    val colonIndex = decoded.indexOf(':')
                    if (colonIndex != -1) {
                        basicClientId = decoded.substring(0, colonIndex)
                        basicClientSecret = decoded.substring(colonIndex + 1)
                    }
                }
            }

            // Extract body parameters (form, json, query)
            val rawBody = runCatching { call.receiveText() }.getOrDefault("")
            val formParameters =
                if (rawBody.isNotBlank() && !rawBody.trimStart().startsWith("{")) {
                    runCatching { io.ktor.http.parseQueryString(rawBody) }.getOrNull()
                } else {
                    null
                }
            val jsonBody =
                if (rawBody.trimStart().startsWith("{")) {
                    runCatching { Json.parseToJsonElement(rawBody).jsonObject }.getOrNull()
                } else {
                    null
                }

            val grantType =
                formParameters?.get("grant_type")
                    ?: jsonBody?.get("grant_type")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: call.request.queryParameters["grant_type"]

            val code =
                formParameters?.get("code")
                    ?: jsonBody?.get("code")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: call.request.queryParameters["code"]

            val redirectUri =
                formParameters?.get("redirect_uri")
                    ?: jsonBody?.get("redirect_uri")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: call.request.queryParameters["redirect_uri"]

            val clientId =
                basicClientId
                    ?: formParameters?.get("client_id")
                    ?: jsonBody?.get("client_id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: call.request.queryParameters["client_id"]

            val clientSecret =
                basicClientSecret
                    ?: formParameters?.get("client_secret")
                    ?: jsonBody?.get("client_secret")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: call.request.queryParameters["client_secret"]

            val codeVerifier =
                formParameters?.get("code_verifier")
                    ?: jsonBody?.get("code_verifier")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: call.request.queryParameters["code_verifier"]

            if (grantType != "authorization_code") {
                val errorJson =
                    buildJsonObject {
                        put("error", "unsupported_grant_type")
                        put("error_description", "grant_type must be authorization_code")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            // Client authentication validation
            if (!authConfig.clientSecret.isNullOrBlank()) {
                if (clientSecret != authConfig.clientSecret || (clientId != null && clientId != authConfig.clientId)) {
                    val errorJson =
                        buildJsonObject {
                            put("error", "invalid_client")
                            put("error_description", "Invalid client credentials")
                        }.toString()
                    call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    return@post
                }
            } else if (clientId != null && clientId != authConfig.clientId) {
                val errorJson =
                    buildJsonObject {
                        put("error", "invalid_client")
                        put("error_description", "Invalid client_id")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (code.isNullOrBlank()) {
                val errorJson =
                    buildJsonObject {
                        put("error", "invalid_grant")
                        put("error_description", "Missing authorization code")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            val authCode = oAuthProvider.consumeAuthorizationCode(code)
            if (authCode == null) {
                val errorJson =
                    buildJsonObject {
                        put("error", "invalid_grant")
                        put("error_description", "Invalid or expired authorization code")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (redirectUri != null && redirectUri != authCode.redirectUri) {
                val errorJson =
                    buildJsonObject {
                        put("error", "invalid_grant")
                        put("error_description", "redirect_uri mismatch")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (clientId != null && clientId != authCode.clientId) {
                val errorJson =
                    buildJsonObject {
                        put("error", "invalid_grant")
                        put("error_description", "client_id mismatch")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            if (!authCode.codeChallenge.isNullOrBlank()) {
                if (codeVerifier.isNullOrBlank() ||
                    !oAuthProvider.verifyPkce(authCode.codeChallenge, codeVerifier, authCode.codeChallengeMethod)
                ) {
                    val errorJson =
                        buildJsonObject {
                            put("error", "invalid_grant")
                            put("error_description", "PKCE verification failed")
                        }.toString()
                    call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            }

            val issuedToken = oAuthProvider.issueAccessToken(clientId ?: authCode.clientId)
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.response.headers.append(HttpHeaders.Pragma, "no-cache")

            val responseJson =
                buildJsonObject {
                    put("access_token", issuedToken.token)
                    put("token_type", "Bearer")
                    put("expires_in", authConfig.tokenExpirationSeconds)
                }.toString()

            call.respondText(responseJson, ContentType.Application.Json, HttpStatusCode.OK)
        }
        val authServerMetadata =
            buildJsonObject {
                put("issuer", baseUrl)
                put("authorization_endpoint", "$baseUrl/oauth/authorize")
                put("token_endpoint", "$baseUrl/oauth/token")
                put(
                    "response_types_supported",
                    buildJsonArray {
                        add("code")
                    },
                )
                put(
                    "grant_types_supported",
                    buildJsonArray {
                        add("authorization_code")
                    },
                )
                put(
                    "code_challenge_methods_supported",
                    buildJsonArray {
                        add("S256")
                        add("plain")
                    },
                )
            }.toString()

        get("/.well-known/oauth-authorization-server") {
            call.respondText(authServerMetadata, ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/.well-known/openid-configuration") {
            call.respondText(authServerMetadata, ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/.well-known/oauth-protected-resource") {
            val protectedResourceMetadata =
                buildJsonObject {
                    put("resource", "$baseUrl/mcp")
                    put(
                        "authorization_servers",
                        buildJsonArray {
                            add(baseUrl)
                        },
                    )
                }.toString()
            call.respondText(protectedResourceMetadata, ContentType.Application.Json, HttpStatusCode.OK)
        }

        fun extractBearerToken(call: ApplicationCall): String? {
            val authHeader = call.request.headers[HttpHeaders.Authorization]
            if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
                return authHeader.substring(7).trim()
            }
            return call.request.queryParameters["token"]
        }

        fun isRequestAuthorized(call: ApplicationCall): Boolean {
            if (!authConfig.isAuthEnabled) return true
            val token = extractBearerToken(call) ?: return false
            return oAuthProvider.isValidAccessToken(token)
        }

        route("/mcp") {
            intercept(ApplicationCallPipeline.Plugins) {
                val call = this.context
                if (call.request.path().startsWith("/mcp") && !isRequestAuthorized(call)) {
                    call.response.headers.append(HttpHeaders.WWWAuthenticate, """Bearer error="invalid_token"""")
                    val errorJson =
                        buildJsonObject {
                            put("error", "unauthorized")
                            put("message", "Invalid or missing Bearer token")
                        }.toString()
                    call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    finish()
                }
            }

            sse("/sse") {
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

            post("/messages") {
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
}
