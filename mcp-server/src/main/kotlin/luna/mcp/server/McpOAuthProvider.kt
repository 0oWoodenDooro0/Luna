package luna.mcp.server

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object McpOAuthTokensTable : Table("mcp_oauth_tokens") {
    val token = varchar("token", 128)
    val refreshToken = varchar("refresh_token", 128).uniqueIndex()
    val clientId = varchar("client_id", 64).index()
    val expiresAt = long("expires_at")
    val issuedAt = long("issued_at")

    override val primaryKey = PrimaryKey(token)
}

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
    val issuedAt: Long = System.currentTimeMillis(),
)

class McpOAuthProvider(
    val config: McpOAuthConfig = McpOAuthConfig(),
    clientRepository: McpClientRepository? = null,
    private val maxCodes: Int = MAX_AUTH_CODES,
    private val maxTokens: Int = MAX_ACCESS_TOKENS,
) {
    private val lock = Any()
    private val secureRandom = SecureRandom()

    var clientRepository: McpClientRepository? = null
        set(value) {
            field = value
            initTokenTable(value)
        }

    init {
        this.clientRepository = clientRepository
    }

    private fun initTokenTable(repo: McpClientRepository?) {
        repo?.let {
            transaction(it.database) {
                if (db.vendor.contains("sqlite", ignoreCase = true)) {
                    exec("PRAGMA journal_mode=WAL;")
                    exec("PRAGMA busy_timeout=5000;")
                }
                @Suppress("DEPRECATION")
                SchemaUtils.createMissingTablesAndColumns(McpOAuthTokensTable)
            }
        }
    }

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

        clientRepository?.let { repo ->
            transaction(repo.database) {
                McpOAuthTokensTable.insert {
                    it[McpOAuthTokensTable.token] = token
                    it[McpOAuthTokensTable.refreshToken] = refreshToken
                    it[McpOAuthTokensTable.clientId] = clientId
                    it[McpOAuthTokensTable.expiresAt] = expiresAt
                    it[McpOAuthTokensTable.issuedAt] = accessToken.issuedAt
                }
            }
        }

        return accessToken
    }

    fun refreshAccessToken(
        refreshToken: String,
        clientId: String?,
    ): AccessToken? {
        var oldToken = synchronized(lock) { refreshTokens[refreshToken] }
        var oldAccessToken = synchronized(lock) { oldToken?.let { accessTokens[it] } }

        if (oldAccessToken == null) {
            clientRepository?.let { repo ->
                transaction(repo.database) {
                    val row =
                        McpOAuthTokensTable
                            .selectAll()
                            .where { McpOAuthTokensTable.refreshToken eq refreshToken }
                            .singleOrNull()
                    if (row != null) {
                        oldToken = row[McpOAuthTokensTable.token]
                        oldAccessToken =
                            AccessToken(
                                token = row[McpOAuthTokensTable.token],
                                refreshToken = row[McpOAuthTokensTable.refreshToken],
                                clientId = row[McpOAuthTokensTable.clientId],
                                expiresAt = row[McpOAuthTokensTable.expiresAt],
                                issuedAt = row[McpOAuthTokensTable.issuedAt],
                            )
                    }
                }
            }
        }

        val currentOldToken = oldAccessToken ?: return null
        if (clientId != null && clientId != currentOldToken.clientId) {
            return null
        }

        val effectiveClientId = currentOldToken.clientId

        val repo = clientRepository
        if (repo != null) {
            val clientRecord = repo.findByClientId(effectiveClientId)
            if (clientRecord == null || currentOldToken.issuedAt < clientRecord.updatedAt) {
                oldToken?.let { tok ->
                    synchronized(lock) {
                        accessTokens.remove(tok)
                        refreshTokens.remove(refreshToken)
                    }
                    transaction(repo.database) {
                        McpOAuthTokensTable.deleteWhere { McpOAuthTokensTable.token eq tok }
                    }
                }
                return null
            }
        }

        val randomBytes = ByteArray(32)
        secureRandom.nextBytes(randomBytes)
        val newToken = "luna_at_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        val expiresAt = System.currentTimeMillis() + (config.tokenExpirationSeconds * 1000L)

        val newAccessToken =
            AccessToken(
                token = newToken,
                refreshToken = refreshToken,
                clientId = effectiveClientId,
                expiresAt = expiresAt,
            )

        synchronized(lock) {
            oldToken?.let { accessTokens.remove(it) }
            accessTokens[newToken] = newAccessToken
            refreshTokens[refreshToken] = newToken
        }

        repo?.let {
            transaction(it.database) {
                oldToken?.let { tok ->
                    McpOAuthTokensTable.deleteWhere { McpOAuthTokensTable.token eq tok }
                }
                McpOAuthTokensTable.insert {
                    it[McpOAuthTokensTable.token] = newToken
                    it[McpOAuthTokensTable.refreshToken] = refreshToken
                    it[McpOAuthTokensTable.clientId] = effectiveClientId
                    it[McpOAuthTokensTable.expiresAt] = expiresAt
                    it[McpOAuthTokensTable.issuedAt] = newAccessToken.issuedAt
                }
            }
        }

        return newAccessToken
    }

    fun findClient(clientId: String?): McpClientRecord? = if (clientId.isNullOrBlank()) null else clientRepository?.findByClientId(clientId)

    fun isValidClientId(clientId: String?): Boolean {
        if (clientId.isNullOrBlank()) return false
        return clientRepository?.findByClientId(clientId) != null
    }

    fun verifyClientSecret(
        clientId: String,
        clientSecret: String?,
    ): Boolean {
        if (clientSecret.isNullOrBlank()) return false
        val dynamicClient = findClient(clientId) ?: return false
        return McpOAuthSecurity.verifySecret(clientSecret, dynamicClient.secretSalt, dynamicClient.secretHash)
    }

    fun getClientIdFromToken(token: String?): String? {
        if (token.isNullOrBlank()) return null

        var accessToken = synchronized(lock) { accessTokens[token] }
        if (accessToken == null) {
            clientRepository?.let { repo ->
                transaction(repo.database) {
                    val row =
                        McpOAuthTokensTable
                            .selectAll()
                            .where { McpOAuthTokensTable.token eq token }
                            .singleOrNull()
                    if (row != null) {
                        accessToken =
                            AccessToken(
                                token = row[McpOAuthTokensTable.token],
                                refreshToken = row[McpOAuthTokensTable.refreshToken],
                                clientId = row[McpOAuthTokensTable.clientId],
                                expiresAt = row[McpOAuthTokensTable.expiresAt],
                                issuedAt = row[McpOAuthTokensTable.issuedAt],
                            )
                    }
                }
            }
        }

        val currentToken = accessToken ?: return null

        if (System.currentTimeMillis() > currentToken.expiresAt) {
            synchronized(lock) {
                accessTokens.remove(token)
                refreshTokens.remove(currentToken.refreshToken)
            }
            clientRepository?.let { repo ->
                transaction(repo.database) {
                    McpOAuthTokensTable.deleteWhere { McpOAuthTokensTable.token eq token }
                }
            }
            return null
        }

        val repo = clientRepository
        if (repo != null) {
            val clientRecord = repo.findByClientId(currentToken.clientId)
            if (clientRecord == null || currentToken.issuedAt < clientRecord.updatedAt) {
                synchronized(lock) {
                    accessTokens.remove(token)
                    refreshTokens.remove(currentToken.refreshToken)
                }
                transaction(repo.database) {
                    McpOAuthTokensTable.deleteWhere { McpOAuthTokensTable.token eq token }
                }
                return null
            }
        }

        synchronized(lock) {
            accessTokens[token] = currentToken
            refreshTokens[currentToken.refreshToken] = token
        }

        return currentToken.clientId
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
        clientRepository?.let { repo ->
            transaction(repo.database) {
                McpOAuthTokensTable.deleteWhere { McpOAuthTokensTable.clientId eq clientId }
            }
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
