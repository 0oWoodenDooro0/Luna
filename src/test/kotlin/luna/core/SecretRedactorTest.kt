package luna.core

import luna.config.EnvReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretRedactorTest {
    @Test
    fun `masks query keys headers bearer tokens and sanitizes endpoints`() {
        val redactor = SecretRedactor()

        assertNull(redactor.redact(null))
        assertEquals(
            "https://example.com/v1?api_key=******&apikey=******&key=******&token=******&secret=******&alt=json",
            redactor.redact(
                "https://example.com/v1?api_key=abc123&apikey=def456&key=ghi789&token=tok111&secret=sec222&alt=json",
            ),
        )
        assertEquals(
            "x-goog-api-key: ****** and x-goog-api-key=\"******\"",
            redactor.redact("x-goog-api-key: headerSecret1 and x-goog-api-key=\"headerSecret2\""),
        )
        assertEquals(
            "Authorization: Bearer ******",
            redactor.redact("Authorization: Bearer my-bearer-token-123"),
        )
        assertEquals(
            "https://api.example.com/data?token=******&secret=******&page=1",
            SecretRedactor.sanitizeEndpoint("https://api.example.com/data?token=tok999&secret=sec888&page=1"),
        )
    }

    @Test
    fun `explicit secrets enforce minimum length of 3 and replace longest-first`() {
        val redactor = SecretRedactor(listOf("ab", "1", "sec", "sec-full-token", "******"))

        val redacted = redactor.redact("ab 1 sec sec-full-token")
        assertEquals("ab 1 ****** ******", redacted)
    }

    @Test
    fun `withExtraSecrets and fromConfig load sensitive values and ignore non-sensitive values`() {
        val env =
            EnvReader.of(
                mapOf(
                    "GEMINI_API_KEY" to "gemini-secret-xyz",
                    "SHORT_KEY" to "xy",
                    "APP_PORT" to "8080",
                ),
            )
        val fromEnv = SecretRedactor.from(env)
        val extended = fromEnv.withExtraSecrets(listOf("runtime-extra-999", "no"))

        val result = extended.redact("key=gemini-secret-xyz short=xy port=8080 extra=runtime-extra-999") ?: ""
        assertFalse(result.contains("gemini-secret-xyz"), "Expected GEMINI_API_KEY to be redacted")
        assertFalse(result.contains("runtime-extra-999"), "Expected extra secret to be redacted")
        assertTrue(result.contains("xy"), "Expected <3 char secret not to be redacted")
        assertTrue(result.contains("8080"), "Expected non-sensitive config value not to be redacted")

        val fromNestedConfig =
            SecretRedactor.fromConfig(
                mapOf(
                    "nested" to mapOf("customToken" to "nested-token-abc"),
                    "normal" to "plain-text-value",
                ),
            )
        val nestedResult = fromNestedConfig.redact("using nested-token-abc and plain-text-value") ?: ""
        assertFalse(nestedResult.contains("nested-token-abc"), "Expected nested sensitive key to be redacted")
        assertTrue(nestedResult.contains("plain-text-value"), "Expected non-sensitive nested value to remain")
    }

    @Test
    fun `redactInlineKeyValues controls inline key-value secret masking`() {
        val sample = "promptToken: 120, api_key=\"inline-key-1\", secret=inline-sec-2, password: 'inline-pw-3'"

        val disabled = SecretRedactor(redactInlineKeyValues = false)
        assertEquals(sample, disabled.redact(sample))

        val enabled = SecretRedactor(redactInlineKeyValues = true).withExtraSecrets(listOf("extra-secret-9"))
        assertEquals(
            "promptToken:******, api_key=\"******\", secret=******, password:'******' ******",
            enabled.redact("$sample extra-secret-9"),
        )
    }
}
