package luna.mcp

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object McpSecurity {
    private val secureRandom = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun generateClientId(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return "luna_cli_" + urlEncoder.encodeToString(bytes)
    }

    fun generateClientSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    fun generateSalt(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }

    fun hashSecret(
        secret: String,
        salt: String,
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val combined = "$salt:$secret".toByteArray(Charsets.UTF_8)
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
            computed.toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8),
        )
    }
}
