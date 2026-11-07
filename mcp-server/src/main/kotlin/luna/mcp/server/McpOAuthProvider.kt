package luna.mcp.server

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

data class AuthorizationCode(
    val code: String,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String?,
    val codeChallengeMethod: String?,
    val expiresAt: Long,
)

data class AccessToken(
    val token: String,
    val refreshToken: String,
    val clientId: String,
    val expiresAt: Long,
)

class McpOAuthProvider(
    val config: McpOAuthConfig = McpOAuthConfig(),
    var clientRepository: McpClientRepository? = null,
    private val maxCodes: Int = MAX_AUTH_CODES,
    private val maxTokens: Int = MAX_ACCESS_TOKENS,
) {
    private val lock = Any()
    private val secureRandom = SecureRandom()

    private val authCodes: MutableMap<String, AuthorizationCode> =
        object : LinkedHashMap<String, AuthorizationCode>(maxCodes, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AuthorizationCode>?): Boolean = size > maxCodes
        }

    private val accessTokens: MutableMap<String, AccessToken> =
        object : LinkedHashMap<String, AccessToken>(maxTokens, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AccessToken>?): Boolean = size > maxTokens
        }

    private val refreshTokens: MutableMap<String, String> =
        object : LinkedHashMap<String, String>(maxTokens, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > maxTokens
        }

    fun isValidRedirectUri(redirectUri: String?): Boolean {
        if (redirectUri.isNullOrBlank()) return false
        if (config.allowedRedirectUris.contains(redirectUri)) return true

        // Google official redirect URI patterns
        if (GOOGLE_REDIRECT_URI_REGEX.matches(redirectUri)) {
            return true
        }

        // Localhost / Loopback patterns
        if (LOCALHOST_REDIRECT_URI_REGEX.matches(redirectUri)) {
            return true
        }

        return false
    }

    fun issueAuthorizationCode(
        clientId: String,
        redirectUri: String,
        codeChallenge: String?,
        codeChallengeMethod: String?,
    ): String {
        val randomBytes = ByteArray(24)
        secureRandom.nextBytes(randomBytes)
        val code = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        val expiresAt = System.currentTimeMillis() + AUTH_CODE_LIFETIME_MS

        val authCode =
            AuthorizationCode(
                code = code,
                clientId = clientId,
                redirectUri = redirectUri,
                codeChallenge = codeChallenge,
                codeChallengeMethod = codeChallengeMethod ?: "plain",
                expiresAt = expiresAt,
            )

        synchronized(lock) {
            authCodes[code] = authCode
        }
        return code
    }

    fun consumeAuthorizationCode(code: String): AuthorizationCode? {
        val authCode =
            synchronized(lock) {
                authCodes.remove(code)
            } ?: return null

        if (System.currentTimeMillis() > authCode.expiresAt) {
            return null
        }
        return authCode
    }

    fun verifyPkce(
        challenge: String,
        verifier: String,
        method: String?,
    ): Boolean =
        when (method) {
            "S256" -> {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(verifier.toByteArray(StandardCharsets.US_ASCII))
                val computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
                computed == challenge
            }

            "plain", null -> {
                verifier == challenge
            }

            else -> {
                false
            }
        }

    fun issueAccessToken(clientId: String): AccessToken {
        val randomBytes = ByteArray(32)
        secureRandom.nextBytes(randomBytes)
        val token = "luna_at_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        val refreshBytes = ByteArray(32)
        secureRandom.nextBytes(refreshBytes)
        val refreshToken = "luna_rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(refreshBytes)
        val expiresAt = System.currentTimeMillis() + (config.tokenExpirationSeconds * 1000L)

        val accessToken =
            AccessToken(
                token = token,
                refreshToken = refreshToken,
                clientId = clientId,
                expiresAt = expiresAt,
            )

        synchronized(lock) {
            accessTokens[token] = accessToken
            refreshTokens[refreshToken] = token
        }
        return accessToken
    }

    fun refreshAccessToken(
        refreshToken: String,
        clientId: String?,
    ): AccessToken? {
        val oldToken = synchronized(lock) { refreshTokens[refreshToken] } ?: return null
        val oldAccessToken = synchronized(lock) { accessTokens[oldToken] } ?: return null
        if (clientId != null && clientId != oldAccessToken.clientId) {
            return null
        }

        val randomBytes = ByteArray(32)
        secureRandom.nextBytes(randomBytes)
        val newToken = "luna_at_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        val expiresAt = System.currentTimeMillis() + (config.tokenExpirationSeconds * 1000L)

        val newAccessToken =
            AccessToken(
                token = newToken,
                refreshToken = refreshToken,
                clientId = oldAccessToken.clientId,
                expiresAt = expiresAt,
            )

        synchronized(lock) {
            accessTokens.remove(oldToken)
            accessTokens[newToken] = newAccessToken
            refreshTokens[refreshToken] = newToken
        }
        return newAccessToken
    }

    fun findClient(clientId: String?): McpClientRecord? = if (clientId.isNullOrBlank()) null else clientRepository?.findByClientId(clientId)

    fun isValidClientId(clientId: String?): Boolean {
        if (clientId.isNullOrBlank()) return false
        if (clientRepository?.findByClientId(clientId) != null) return true
        return !config.clientId.isNullOrBlank() && clientId == config.clientId
    }

    fun verifyClientSecret(
        clientId: String,
        clientSecret: String?,
    ): Boolean {
        val dynamicClient = findClient(clientId)
        if (dynamicClient != null) {
            if (clientSecret.isNullOrBlank()) return false
            return McpOAuthSecurity.verifySecret(clientSecret, dynamicClient.secretSalt, dynamicClient.secretHash)
        }
        if (!config.clientId.isNullOrBlank() && clientId == config.clientId) {
            if (!config.clientSecret.isNullOrBlank()) {
                return clientSecret != null &&
                    MessageDigest.isEqual(
                        clientSecret.toByteArray(StandardCharsets.UTF_8),
                        config.clientSecret.toByteArray(StandardCharsets.UTF_8),
                    )
            }
            return true
        }
        return false
    }

    fun getClientIdFromToken(token: String?): String? {
        if (token.isNullOrBlank()) return null
        if (!config.staticAuthToken.isNullOrBlank() && token == config.staticAuthToken) {
            return config.clientId
        }

        val accessToken =
            synchronized(lock) {
                accessTokens[token]
            } ?: return null

        if (System.currentTimeMillis() > accessToken.expiresAt) {
            synchronized(lock) {
                accessTokens.remove(token)
                refreshTokens.remove(accessToken.refreshToken)
            }
            return null
        }
        return accessToken.clientId
    }

    fun isValidAccessToken(token: String?): Boolean = getClientIdFromToken(token) != null

    fun revokeClientTokens(clientId: String) {
        synchronized(lock) {
            val tokensToRemove = accessTokens.values.filter { it.clientId == clientId }
            for (at in tokensToRemove) {
                accessTokens.remove(at.token)
                refreshTokens.remove(at.refreshToken)
            }
            authCodes.entries.removeIf { it.value.clientId == clientId }
        }
    }

    companion object {
        const val MAX_AUTH_CODES = 1000
        const val MAX_ACCESS_TOKENS = 1000
        const val AUTH_CODE_LIFETIME_MS = 600_000L // 10 minutes

        private val GOOGLE_REDIRECT_URI_REGEX =
            Regex("""^https://oauth-redirect\.googleusercontent\.com/(?:r|a)/.*$""")
        private val LOCALHOST_REDIRECT_URI_REGEX =
            Regex("""^https?://(?:localhost|127\.0\.0\.1)(?::\d+)?(?:/.*)?$""")
    }
}
