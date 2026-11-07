package luna.mcp.server

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Security utilities for OAuth 2.1 client credential hashing and constant-time verification.
 */
object McpOAuthSecurity {
    fun hashSecret(
        secret: String,
        salt: String,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val combined = "$salt:$secret".toByteArray(StandardCharsets.UTF_8)
        val digest = md.digest(combined)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifySecret(
        secret: String,
        salt: String,
        expectedHash: String,
    ): Boolean {
        val computed = hashSecret(secret, salt)
        return MessageDigest.isEqual(
            computed.toByteArray(StandardCharsets.UTF_8),
            expectedHash.toByteArray(StandardCharsets.UTF_8),
        )
    }
}
