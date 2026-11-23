package luna.core.ai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeminiHttpClientFactoryTest {
    @Serializable
    private data class SamplePayload(
        val required: String,
        val optionalDefault: String = "default-value",
        val nullableField: String? = null,
    )

    @Test
    fun `canonical json ignores unknown keys, is lenient, and omits defaults and nulls`() {
        val decoded =
            GeminiHttpClientFactory.json.decodeFromString<SamplePayload>(
                """{required: ok, unknownExtra: 123}""",
            )
        assertEquals("ok", decoded.required)

        val encoded = GeminiHttpClientFactory.json.encodeToString(SamplePayload.serializer(), SamplePayload("hi"))
        assertEquals("""{"required":"hi"}""", encoded)
    }

    @Test
    fun `create installs ContentNegotiation and default HttpTimeout values`() =
        runTest {
            var capturedTimeout: HttpTimeoutConfig? = null
            val engine =
                MockEngine { request ->
                    capturedTimeout = request.getCapabilityOrNull(HttpTimeoutCapability)
                    respond("{}", HttpStatusCode.OK)
                }

            val client = GeminiHttpClientFactory.create(engine = engine)
            assertNotNull(client.pluginOrNull(ContentNegotiation), "ContentNegotiation must be installed by default")
            assertNotNull(client.pluginOrNull(HttpTimeout), "HttpTimeout must be installed by default")

            client.get("https://example.com/test")
            val timeout = assertNotNull(capturedTimeout, "HttpTimeout capability must be attached to requests")
            assertEquals(GeminiHttpClientFactory.DEFAULT_REQUEST_TIMEOUT_MS, timeout.requestTimeoutMillis)
            assertEquals(GeminiHttpClientFactory.DEFAULT_CONNECT_TIMEOUT_MS, timeout.connectTimeoutMillis)
            assertEquals(GeminiHttpClientFactory.DEFAULT_SOCKET_TIMEOUT_MS, timeout.socketTimeoutMillis)
            client.close()
        }

    @Test
    fun `create supports custom timeouts and disabling ContentNegotiation`() =
        runTest {
            var capturedTimeout: HttpTimeoutConfig? = null
            val engine =
                MockEngine { request ->
                    capturedTimeout = request.getCapabilityOrNull(HttpTimeoutCapability)
                    respond("{}", HttpStatusCode.OK)
                }

            val client =
                GeminiHttpClientFactory.create(
                    engine = engine,
                    requestTimeoutMs = 5_000L,
                    connectTimeoutMs = 2_000L,
                    socketTimeoutMs = 6_000L,
                    installContentNegotiation = false,
                )
            assertNull(
                client.pluginOrNull(ContentNegotiation),
                "ContentNegotiation must be omitted when installContentNegotiation=false",
            )
            assertNotNull(client.pluginOrNull(HttpTimeout), "HttpTimeout must still be installed")

            client.get("https://example.com/test")
            val timeout = assertNotNull(capturedTimeout, "HttpTimeout capability must be attached to requests")
            assertEquals(5_000L, timeout.requestTimeoutMillis)
            assertEquals(2_000L, timeout.connectTimeoutMillis)
            assertEquals(6_000L, timeout.socketTimeoutMillis)
            client.close()
        }

    @Test
    fun `create without engine builds active CIO HttpClient`() {
        val client = GeminiHttpClientFactory.create()
        assertNotNull(client.pluginOrNull(ContentNegotiation), "Default CIO client must install ContentNegotiation")
        assertNotNull(client.pluginOrNull(HttpTimeout), "Default CIO client must install HttpTimeout")
        assertTrue(client.isActive, "Created CIO HttpClient must be active")
        client.close()
        assertFalse(client.isActive, "Closed CIO HttpClient must not remain active")
    }
}
