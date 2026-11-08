package luna.mcp.server

import io.ktor.http.HttpHeaders
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Collaborator component responsible for extracting and decoding OAuth 2.1 token parameters
 * from incoming HTTP requests (Basic Authorization, form URL-encoded, JSON, or query parameters).
 */
object OAuthTokenRequestParser {
    suspend fun parse(call: ApplicationCall): OAuthTokenRequest {
        val authHeader = call.request.headers[HttpHeaders.Authorization]
        var basicClientId: String? = null
        var basicClientSecret: String? = null

        if (authHeader != null && authHeader.startsWith("Basic ", ignoreCase = true)) {
            runCatching {
                val base64Credentials = authHeader.substring(6).trim()
                val decodedBytes =
                    runCatching { Base64.getDecoder().decode(base64Credentials) }
                        .getOrElse { Base64.getMimeDecoder().decode(base64Credentials) }
                val decoded = String(decodedBytes, StandardCharsets.UTF_8)
                val colonIndex = decoded.indexOf(':')
                if (colonIndex != -1) {
                    val rawClientId = decoded.substring(0, colonIndex)
                    val rawClientSecret = decoded.substring(colonIndex + 1)
                    basicClientId = runCatching { URLDecoder.decode(rawClientId, StandardCharsets.UTF_8.name()) }.getOrDefault(rawClientId)
                    basicClientSecret = runCatching { URLDecoder.decode(rawClientSecret, StandardCharsets.UTF_8.name()) }.getOrDefault(rawClientSecret)
                }
            }
        }

        val rawBody = runCatching { call.receiveText() }.getOrDefault("")
        val formParameters =
            if (rawBody.isNotBlank() && !rawBody.trimStart().startsWith("{")) {
                runCatching { parseQueryString(rawBody) }.getOrNull()
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
                ?: jsonBody?.get("grant_type")?.let { (it as? JsonPrimitive)?.content }
                ?: call.request.queryParameters["grant_type"]

        val code =
            formParameters?.get("code")
                ?: jsonBody?.get("code")?.let { (it as? JsonPrimitive)?.content }
                ?: call.request.queryParameters["code"]

        val redirectUri =
            formParameters?.get("redirect_uri")
                ?: jsonBody?.get("redirect_uri")?.let { (it as? JsonPrimitive)?.content }
                ?: call.request.queryParameters["redirect_uri"]

        val clientId =
            basicClientId
                ?: formParameters?.get("client_id")
                ?: jsonBody?.get("client_id")?.let { (it as? JsonPrimitive)?.content }
                ?: call.request.queryParameters["client_id"]

        val clientSecret =
            basicClientSecret
                ?: formParameters?.get("client_secret")
                ?: jsonBody?.get("client_secret")?.let { (it as? JsonPrimitive)?.content }
                ?: call.request.queryParameters["client_secret"]

        val codeVerifier =
            formParameters?.get("code_verifier")
                ?: jsonBody?.get("code_verifier")?.let { (it as? JsonPrimitive)?.content }
                ?: call.request.queryParameters["code_verifier"]

        val refreshToken =
            formParameters?.get("refresh_token")
                ?: jsonBody?.get("refresh_token")?.let { (it as? JsonPrimitive)?.content }
                ?: call.request.queryParameters["refresh_token"]

        return OAuthTokenRequest(
            grantType = grantType?.trim(),
            code = code?.trim(),
            redirectUri = redirectUri?.trim(),
            clientId = clientId?.trim(),
            clientSecret = clientSecret?.trim(),
            codeVerifier = codeVerifier?.trim(),
            refreshToken = refreshToken?.trim(),
        )
    }
}
