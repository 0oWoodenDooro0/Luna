package luna.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpSecurityTest {
    @Test
    fun `generateClientId produces id with luna_cli_ prefix and url safe characters`() {
        val clientId = McpSecurity.generateClientId()
        assertTrue(clientId.startsWith("luna_cli_"))
        assertTrue(clientId.length >= 24)
        // Ensure no +, /, or = characters (URL safe and no padding)
        val suffix = clientId.removePrefix("luna_cli_")
        assertTrue(suffix.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    fun `generateClientSecret produces 32 byte url safe base64 string`() {
        val secret1 = McpSecurity.generateClientSecret()
        val secret2 = McpSecurity.generateClientSecret()

        assertNotEquals(secret1, secret2)
        assertTrue(secret1.matches(Regex("^[A-Za-z0-9_-]{43}$")))
    }

    @Test
    fun `generateSalt produces 16 byte url safe base64 string`() {
        val salt1 = McpSecurity.generateSalt()
        val salt2 = McpSecurity.generateSalt()

        assertNotEquals(salt1, salt2)
        assertTrue(salt1.matches(Regex("^[A-Za-z0-9_-]{22}$")))
    }

    @Test
    fun `hashSecret produces 64 character sha256 hex string`() {
        val secret = "test-secret"
        val salt = "test-salt"
        val hash = McpSecurity.hashSecret(secret, salt)

        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))

        // Deterministic
        assertEquals(hash, McpSecurity.hashSecret(secret, salt))

        // Different secret or salt produces different hash
        assertNotEquals(hash, McpSecurity.hashSecret("other-secret", salt))
        assertNotEquals(hash, McpSecurity.hashSecret(secret, "other-salt"))
    }

    @Test
    fun `verifySecret verifies correct secret and rejects incorrect secret`() {
        val secret = McpSecurity.generateClientSecret()
        val salt = McpSecurity.generateSalt()
        val hash = McpSecurity.hashSecret(secret, salt)

        assertTrue(McpSecurity.verifySecret(secret, salt, hash))
        assertFalse(McpSecurity.verifySecret("wrong-secret", salt, hash))
        assertFalse(McpSecurity.verifySecret(secret, "wrong-salt", hash))
    }
}
