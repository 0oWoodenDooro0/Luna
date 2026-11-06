package luna.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpDiscordOAuthClientTest {
    @Test
    fun `constructor accepts explicit clientId and clientSecret without reading system env`() {
        val client =
            HttpDiscordOAuthClient(
                clientId = "explicit-client-id",
                clientSecret = "explicit-client-secret",
            )

        val authUrl = client.getAuthorizationUrl("https://example.com/callback")
        assertTrue(authUrl.contains("client_id=explicit-client-id"))
        assertTrue(authUrl.contains("redirect_uri=https%3A%2F%2Fexample.com%2Fcallback"))
    }

    @Test
    fun `getAuthorizationUrl throws IllegalArgumentException when clientId is blank`() {
        val client = HttpDiscordOAuthClient(clientId = "", clientSecret = "")
        assertThrows<IllegalArgumentException> {
            client.getAuthorizationUrl("https://example.com/callback")
        }
    }
}
