package luna.mcp.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.ServerSSESession
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
    clientRepository: McpClientRepository? = null,
    oAuthProvider: McpOAuthProvider = McpOAuthProvider(authConfig, clientRepository),
    webhookRepository: McpWebhookRepository = broadcaster.repository,
) {
    if (clientRepository != null && oAuthProvider.clientRepository == null) {
        oAuthProvider.clientRepository = clientRepository
    }

    install(SSE)
    install(ContentNegotiation) {
        json()
    }

    val handler = McpProtocolHandler(broadcaster, webhookRepository)
    val baseUrl = authConfig.normalizedBaseUrl

    fun isValidClient(clientId: String?): Boolean = oAuthProvider.isValidClientId(clientId)

    fun verifyClientCredentials(
        clientId: String?,
        clientSecret: String?,
    ): Boolean {
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) return false
        val dynamicClient = oAuthProvider.findClient(clientId) ?: return false
        return oAuthProvider.verifyClientSecret(clientId, clientSecret)
    }

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

            if (!isValidClient(clientId)) {
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
                    clientId = clientId!!,
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
            val tokenRequest = OAuthTokenRequestParser.parse(call)
            val grantType = tokenRequest.grantType
            val code = tokenRequest.code
            val redirectUri = tokenRequest.redirectUri
            val clientId = tokenRequest.clientId
            val clientSecret = tokenRequest.clientSecret
            val codeVerifier = tokenRequest.codeVerifier
            val refreshToken = tokenRequest.refreshToken

            if (grantType != "authorization_code" && grantType != "refresh_token") {
                val errorJson =
                    buildJsonObject {
                        put("error", "unsupported_grant_type")
                        put("error_description", "grant_type must be authorization_code or refresh_token")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@post
            }

            // Client authentication validation
            if (!clientSecret.isNullOrBlank()) {
                if (!verifyClientCredentials(clientId, clientSecret)) {
                    val errorJson =
                        buildJsonObject {
                            put("error", "invalid_client")
                            put("error_description", "Client authentication failed")
                        }.toString()
                    call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    return@post
                }
            } else if (clientId != null) {
                val dynamicClient = oAuthProvider.findClient(clientId)
                if (dynamicClient != null && dynamicClient.secretHash.isNotBlank()) {
                    // Confidential client requires secret
                    val errorJson =
                        buildJsonObject {
                            put("error", "invalid_client")
                            put("error_description", "Client authentication failed")
                        }.toString()
                    call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    return@post
                } else if (dynamicClient == null) {
                    val errorJson =
                        buildJsonObject {
                            put("error", "invalid_client")
                            put("error_description", "Invalid client_id")
                        }.toString()
                    call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
            }

            if (grantType == "refresh_token") {
                if (refreshToken.isNullOrBlank()) {
                    val errorJson =
                        buildJsonObject {
                            put("error", "invalid_request")
                            put("error_description", "Missing refresh_token")
                        }.toString()
                    call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }
                val refreshed = oAuthProvider.refreshAccessToken(refreshToken, clientId)
                if (refreshed == null) {
                    val errorJson =
                        buildJsonObject {
                            put("error", "invalid_grant")
                            put("error_description", "Invalid or expired refresh_token")
                        }.toString()
                    call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.BadRequest)
                    return@post
                }

                if (clientId == null) {
                    val dynamicClient = oAuthProvider.findClient(refreshed.clientId)
                    if (dynamicClient != null) {
                        if (clientSecret.isNullOrBlank() || !verifyClientCredentials(refreshed.clientId, clientSecret)) {
                            val errorJson =
                                buildJsonObject {
                                    put("error", "invalid_client")
                                    put("error_description", "Client authentication failed")
                                }.toString()
                            call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                            return@post
                        }
                    }
                }
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.response.headers.append(HttpHeaders.Pragma, "no-cache")
                val responseJson =
                    buildJsonObject {
                        put("access_token", refreshed.token)
                        put("token_type", "Bearer")
                        put("expires_in", authConfig.tokenExpirationSeconds)
                        put("refresh_token", refreshed.refreshToken)
                    }.toString()
                call.respondText(responseJson, ContentType.Application.Json, HttpStatusCode.OK)
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

            val effectiveClientId = clientId ?: authCode.clientId
            if (clientId == null && !verifyClientCredentials(effectiveClientId, clientSecret)) {
                val errorJson =
                    buildJsonObject {
                        put("error", "invalid_client")
                        put("error_description", "Client authentication failed")
                    }.toString()
                call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.Unauthorized)
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
                    put("refresh_token", issuedToken.refreshToken)
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
                        add("refresh_token")
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

        get("/.well-known/oauth-protected-resource/{path...}") {
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

        fun resolveClientId(call: ApplicationCall): String? {
            val token = extractBearerToken(call)
            if (token != null) {
                val tokenClientId = oAuthProvider.getClientIdFromToken(token)
                if (!tokenClientId.isNullOrBlank()) {
                    return tokenClientId
                }
            }

            val sessionId = call.request.queryParameters["sessionId"] ?: call.request.headers["Mcp-Session-Id"]
            if (sessionId != null) {
                val sessionClientId = sessionManager.getClientId(sessionId)
                if (!sessionClientId.isNullOrBlank()) {
                    return sessionClientId
                }
            }

            return null
        }

        fun isRequestAuthorized(call: ApplicationCall): Boolean = resolveClientId(call) != null

        route("/mcp") {
            intercept(ApplicationCallPipeline.Plugins) {
                val call = this.context
                if (call.request.httpMethod == HttpMethod.Options || call.request.httpMethod == HttpMethod.Head) {
                    call.respond(HttpStatusCode.OK)
                    finish()
                    return@intercept
                }
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

            suspend fun handleSseSession(session: ServerSSESession) {
                val clientId = resolveClientId(session.call)
                if (clientId == null) {
                    session.call.response.headers
                        .append(HttpHeaders.WWWAuthenticate, """Bearer error="invalid_token"""")
                    val errorJson =
                        buildJsonObject {
                            put("error", "unauthorized")
                            put("message", "Invalid or missing Bearer token")
                        }.toString()
                    session.call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    return
                }
                val sessionId = UUID.randomUUID().toString()
                val channel = Channel<String>(capacity = 64)
                sessionManager.registerSession(sessionId, channel, clientId)
                try {
                    session.send(ServerSentEvent(data = "/mcp/messages?sessionId=$sessionId", event = "endpoint"))
                    for (message in channel) {
                        session.send(ServerSentEvent(data = message, event = "message"))
                    }
                } finally {
                    sessionManager.removeSession(sessionId)
                }
            }

            sse("/sse") {
                handleSseSession(this)
            }

            sse {
                handleSseSession(this)
            }

            suspend fun handleMessage(call: ApplicationCall) {
                val sessionId = call.request.queryParameters["sessionId"] ?: call.request.headers["Mcp-Session-Id"]
                val requestBody = call.receiveText()
                if (requestBody.isBlank()) {
                    call.respond(HttpStatusCode.NoContent)
                    return
                }
                val clientId = resolveClientId(call)
                if (clientId == null) {
                    call.response.headers.append(HttpHeaders.WWWAuthenticate, """Bearer error="invalid_token"""")
                    val errorJson =
                        buildJsonObject {
                            put("error", "unauthorized")
                            put("message", "Invalid or missing Bearer token")
                        }.toString()
                    call.respondText(errorJson, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                    return
                }
                val responseString = handler.handle(requestBody, clientId)

                if (responseString == null) {
                    call.respond(HttpStatusCode.Accepted)
                } else {
                    if (sessionId != null) {
                        sessionManager.sendToSession(sessionId, responseString)
                    }
                    call.respondText(responseString, ContentType.Application.Json, HttpStatusCode.OK)
                }
            }

            post("/sse") {
                handleMessage(call)
            }

            post("/messages") {
                handleMessage(call)
            }

            post {
                handleMessage(call)
            }

            head("/sse") {
                call.respond(HttpStatusCode.OK)
            }

            head {
                call.respond(HttpStatusCode.OK)
            }

            delete("/sse") {
                call.respond(HttpStatusCode.OK)
            }

            delete {
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
