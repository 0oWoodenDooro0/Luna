package luna.mcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpOAuthSecurityTest {
    @Test
    fun `hashSecret produces 64 character sha256 hex string`() {
        val secret = "super-secret-token"
        val salt = "random-salt"
        val hash = McpOAuthSecurity.hashSecret(secret, salt)

        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `hashSecret is deterministic for same input and sensitive to salt or secret changes`() {
        val secret = "my-secret"
        val salt = "my-salt"
        val hash = McpOAuthSecurity.hashSecret(secret, salt)

        assertEquals(hash, McpOAuthSecurity.hashSecret(secret, salt))
        assertNotEquals(hash, McpOAuthSecurity.hashSecret("other-secret", salt))
        assertNotEquals(hash, McpOAuthSecurity.hashSecret(secret, "other-salt"))
    }

    @Test
    fun `verifySecret succeeds with correct secret and fails with wrong secret`() {
        val secret = "correct-password-123"
        val salt = "salt-xyz"
        val expectedHash = McpOAuthSecurity.hashSecret(secret, salt)

        assertTrue(McpOAuthSecurity.verifySecret(secret, salt, expectedHash))
        assertFalse(McpOAuthSecurity.verifySecret("wrong-password", salt, expectedHash))
        assertFalse(McpOAuthSecurity.verifySecret(secret, "wrong-salt", expectedHash))
    }
}
