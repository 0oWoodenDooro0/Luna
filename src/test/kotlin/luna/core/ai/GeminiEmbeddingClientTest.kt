package luna.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeminiEmbeddingClientTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `embedQuery sends RETRIEVAL_QUERY with 768d and L2-normalizes output`() =
        runTest {
            val rawValues =
                List(768) { index ->
                    if (index == 0) {
                        3.0f
                    } else if (index == 1) {
                        4.0f
                    } else {
                        0.0f
                    }
                }
            var capturedUrl: String? = null
            var capturedApiKey: String? = null
            var capturedBody: String? = null

            val mockEngine =
                MockEngine { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    capturedUrl = request.url.toString()
                    capturedApiKey = request.headers["x-goog-api-key"]
                    capturedBody = (request.body as TextContent).text
                    val responseValues = rawValues.joinToString(",")
                    respond(
                        content = """{"embedding":{"values":[$responseValues]}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

            val client =
                GeminiEmbeddingClient(
                    httpClient = HttpClient(mockEngine),
                    apiKey = "test-api-key",
                    baseUrl = "https://generativelanguage.googleapis.com",
                )

            val vector = client.embedQuery("What coffee does Alice like?")

            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent",
                capturedUrl,
            )
            assertEquals("test-api-key", capturedApiKey)

            val reqJson = json.parseToJsonElement(requireNotNull(capturedBody)).jsonObject
            assertEquals("models/gemini-embedding-001", reqJson["model"]?.jsonPrimitive?.content)
            assertEquals("RETRIEVAL_QUERY", reqJson["taskType"]?.jsonPrimitive?.content)
            assertEquals(768, reqJson["outputDimensionality"]?.jsonPrimitive?.int)
            assertEquals(
                "What coffee does Alice like?",
                reqJson["content"]
                    ?.jsonObject
                    ?.get("parts")
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content,
            )

            assertEquals(768, vector.size)
            assertEquals(0.6f, vector[0], 1e-5f)
            assertEquals(0.8f, vector[1], 1e-5f)
            val norm = sqrt(vector.fold(0.0) { acc, v -> acc + v * v })
            assertEquals(1.0, norm, 1e-5)
        }

    @Test
    fun `embedDocuments sends batchEmbedContents with RETRIEVAL_DOCUMENT and skips empty input`() =
        runTest {
            var requestCount = 0
            var capturedUrl: String? = null
            var capturedBody: String? = null

            val mockEngine =
                MockEngine { request ->
                    requestCount++
                    capturedUrl = request.url.toString()
                    capturedBody = (request.body as TextContent).text
                    val vecA = List(768) { if (it == 0) 2.0f else 0.0f }.joinToString(",")
                    val vecB = List(768) { if (it == 1) 5.0f else 0.0f }.joinToString(",")
                    respond(
                        content = """{"embeddings":[{"values":[$vecA]},{"values":[$vecB]}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

            val client =
                GeminiEmbeddingClient(
                    httpClient = HttpClient(mockEngine),
                    apiKey = "test-api-key",
                )

            val emptyResult = client.embedDocuments(emptyList())
            assertTrue(emptyResult.isEmpty(), "Empty input list should return empty embedding list")
            assertEquals(0, requestCount)

            val results = client.embedDocuments(listOf("fact one", "fact two"))
            assertEquals(1, requestCount)
            assertEquals(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents",
                capturedUrl,
            )

            val reqJson = json.parseToJsonElement(requireNotNull(capturedBody)).jsonObject
            val requests = reqJson["requests"]?.jsonArray
            assertEquals(2, requests?.size)
            assertEquals(
                "RETRIEVAL_DOCUMENT",
                requests
                    ?.get(0)
                    ?.jsonObject
                    ?.get("taskType")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                768,
                requests
                    ?.get(1)
                    ?.jsonObject
                    ?.get("outputDimensionality")
                    ?.jsonPrimitive
                    ?.int,
            )

            assertEquals(2, results.size)
            assertEquals(1.0f, results[0][0], 1e-5f)
            assertEquals(1.0f, results[1][1], 1e-5f)
        }

    @Test
    fun `rejects zero-norm vectors and NaN outputs`() =
        runTest {
            val zeroVector = List(768) { 0.0f }.joinToString(",")
            val mockZeroEngine =
                MockEngine {
                    respond(
                        content = """{"embedding":{"values":[$zeroVector]}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }

            val zeroClient = GeminiEmbeddingClient(httpClient = HttpClient(mockZeroEngine), apiKey = "k")
            assertFailsWith<GeminiEmbeddingException> {
                zeroClient.embedQuery("hello")
            }

            assertFailsWith<GeminiEmbeddingException> {
                GeminiEmbeddingClient.l2Normalize(List(768) { if (it == 0) Float.NaN else 1.0f })
            }
        }

    @Test
    fun `transient HTTP error throws without caching failure so retry succeeds`() =
        runTest {
            var attempts = 0
            val validVec = List(768) { if (it == 0) 1.0f else 0.0f }.joinToString(",")
            val mockEngine =
                MockEngine {
                    attempts++
                    if (attempts == 1) {
                        respond(
                            content = """{"error":{"code":429,"message":"Rate limit"}}""",
                            status = HttpStatusCode.TooManyRequests,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    } else {
                        respond(
                            content = """{"embedding":{"values":[$validVec]}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    }
                }

            val client = GeminiEmbeddingClient(httpClient = HttpClient(mockEngine), apiKey = "k")
            val ex =
                assertFailsWith<GeminiEmbeddingException> {
                    client.embedQuery("retry me")
                }
            assertEquals(429, ex.statusCode)

            val retried = client.embedQuery("retry me")
            assertEquals(768, retried.size)
            assertEquals(1.0f, retried[0], 1e-5f)
            assertEquals(2, attempts)
        }
}
