package luna.core.ai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import luna.chatter.ChatterConfig
import luna.chatter.ChatterGenerator
import luna.chatter.GeminiDecisionClient
import luna.summarizer.GeminiConfig
import luna.summarizer.GeminiSummarizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeminiHttpClientSharingTest {
    @Test
    fun `GeminiEmbeddingClient applies per-request timeout override on shared HttpClient`() =
        runTest {
            var capturedTimeout: HttpTimeoutConfig? = null
            val vector = List(GeminiEmbeddingClient.DEFAULT_OUTPUT_DIMENSIONALITY) { idx -> if (idx == 0) 1.0f else 0.0f }
            val engine =
                MockEngine { request ->
                    capturedTimeout = request.getCapabilityOrNull(HttpTimeoutCapability)
                    respond(
                        content = """{"embedding":{"values":[${vector.joinToString(",")}]}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val sharedClient = GeminiHttpClientFactory.create(engine = engine)
            val embeddingClient =
                GeminiEmbeddingClient(
                    httpClient = sharedClient,
                    apiKey = "test-key",
                    timeoutMs = 7_500L,
                )

            embeddingClient.embedQuery("hello")

            val timeout = assertNotNull(capturedTimeout, "HttpTimeout capability must be present on embedding request")
            assertEquals(7_500L, timeout.requestTimeoutMillis)
            assertEquals(7_500L, timeout.connectTimeoutMillis)
            assertEquals(7_500L, timeout.socketTimeoutMillis)
            sharedClient.close()
        }

    @Test
    fun `GeminiInteractionClient applies per-request timeout overrides on POST and GET polling`() =
        runTest {
            val capturedTimeouts = mutableListOf<HttpTimeoutConfig?>()
            var callCount = 0
            val engine =
                MockEngine { request ->
                    capturedTimeouts += request.getCapabilityOrNull(HttpTimeoutCapability)
                    callCount++
                    val payload =
                        if (callCount == 1) {
                            """{"id":"int-1","status":"in_progress"}"""
                        } else {
                            """{"id":"int-1","status":"completed","outputs":[{"type":"text","text":"done"}]}"""
                        }
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val sharedClient = GeminiHttpClientFactory.create(engine = engine)
            val interactionClient =
                GeminiInteractionClient(
                    httpClient = sharedClient,
                    pollingDelayMs = 1L,
                    maxPollingAttempts = 3,
                    requestTimeoutMs = 10_000L,
                    connectTimeoutMs = 5_000L,
                    socketTimeoutMs = 10_000L,
                )

            val result =
                interactionClient.interact(
                    baseUrl = "https://generativelanguage.googleapis.com",
                    apiKey = "test-key",
                    request =
                        GeminiInteractionRequest(
                            model = "gemini-2.5-flash",
                            input = GeminiInteractionClient.buildMultimodalInputs("hi"),
                            background = true,
                        ),
                )

            assertEquals(2, capturedTimeouts.size, "Expected POST and GET polling requests: $result")
            capturedTimeouts.forEach { timeout ->
                val nonNullTimeout = assertNotNull(timeout, "HttpTimeout capability must be present on interaction request")
                assertEquals(10_000L, nonNullTimeout.requestTimeoutMillis)
                assertEquals(5_000L, nonNullTimeout.connectTimeoutMillis)
                assertEquals(10_000L, nonNullTimeout.socketTimeoutMillis)
            }
            sharedClient.close()
        }

    @Test
    fun `GeminiDecisionClient preserves config timeoutMillis override on shared HttpClient`() =
        runTest {
            var capturedTimeout: HttpTimeoutConfig? = null
            val engine =
                MockEngine { request ->
                    capturedTimeout = request.getCapabilityOrNull(HttpTimeoutCapability)
                    respond(
                        content = """{"answer":{"shouldParticipate":true,"confidence":0.9}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val sharedClient = GeminiHttpClientFactory.create(engine = engine)
            val decisionClient =
                GeminiDecisionClient(
                    config = ChatterConfig(apiKey = "test-key", decision = ChatterConfig.Decision(timeoutMillis = 2_500L)),
                    httpClient = sharedClient,
                )

            decisionClient.shouldParticipate(state = "hello")

            val timeout = assertNotNull(capturedTimeout, "HttpTimeout capability must be present on decision request")
            assertEquals(2_500L, timeout.requestTimeoutMillis)
            assertEquals(2_500L, timeout.connectTimeoutMillis)
            assertEquals(2_500L, timeout.socketTimeoutMillis)
            sharedClient.close()
        }

    @Test
    fun `injected HttpClient is not closed when individual AI clients close`() {
        val sharedClient = GeminiHttpClientFactory.create()
        val decisionClient =
            GeminiDecisionClient(
                config = ChatterConfig(apiKey = "test-key"),
                httpClient = sharedClient,
            )
        val generator =
            ChatterGenerator(
                config = ChatterConfig(apiKey = "test-key"),
                httpClient = sharedClient,
            )
        val embeddingClient =
            GeminiEmbeddingClient(
                httpClient = sharedClient,
                apiKey = "test-key",
            )
        val summarizer =
            GeminiSummarizer(
                config = GeminiConfig(apiKey = "test-key"),
                httpClient = sharedClient,
            )

        decisionClient.close()
        generator.close()
        embeddingClient.close()
        summarizer.close()

        assertTrue(sharedClient.isActive, "Injected shared HttpClient must remain active after child close()")
        sharedClient.close()
    }

    @Test
    fun `standalone AI clients close their internally created HttpClient without error`() {
        val decisionClient = GeminiDecisionClient(config = ChatterConfig(apiKey = "test-key"))
        val generator = ChatterGenerator(config = ChatterConfig(apiKey = "test-key"))
        val embeddingClient = GeminiEmbeddingClient(apiKey = "test-key")
        val summarizer = GeminiSummarizer(config = GeminiConfig(apiKey = "test-key"))

        decisionClient.close()
        generator.close()
        embeddingClient.close()
        summarizer.close()
    }
}
