package luna.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.SocketTimeoutException
import kotlin.math.sqrt

/**
 * Exception thrown when a Gemini Embedding API request or vector validation fails.
 */
class GeminiEmbeddingException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Serializable
private data class EmbedContentPart(
    val text: String,
)

@Serializable
private data class EmbedContentPayload(
    val parts: List<EmbedContentPart>,
)

@Serializable
private data class EmbedContentRequest(
    val model: String,
    val content: EmbedContentPayload,
    val taskType: String,
    val outputDimensionality: Int,
)

@Serializable
private data class BatchEmbedContentsRequest(
    val requests: List<EmbedContentRequest>,
)

@Serializable
private data class EmbeddingValuesResponse(
    val values: List<Float> = emptyList(),
)

@Serializable
private data class EmbedContentResponse(
    val embedding: EmbeddingValuesResponse? = null,
)

@Serializable
private data class BatchEmbedContentsResponse(
    val embeddings: List<EmbeddingValuesResponse> = emptyList(),
)

/**
 * Client for the Google Gemini Embedding API (`gemini-embedding-001`) supporting
 * single-query embedding (`:embedContent`, `RETRIEVAL_QUERY`) and batch document embedding
 * (`:batchEmbedContents`, `RETRIEVAL_DOCUMENT`) truncated to 768 dimensions with mandatory
 * client-side L2 normalization.
 */
class GeminiEmbeddingClient(
    httpClient: HttpClient? = null,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val outputDimensionality: Int = DEFAULT_OUTPUT_DIMENSIONALITY,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : AutoCloseable {
    private val ownsHttpClient: Boolean = httpClient == null
    private val httpClient: HttpClient =
        httpClient
            ?: GeminiHttpClientFactory.create(
                requestTimeoutMs = timeoutMs,
                connectTimeoutMs = timeoutMs,
                socketTimeoutMs = timeoutMs,
                installContentNegotiation = false,
            )
    private val json = Json { ignoreUnknownKeys = true }
    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val modelResource = if (model.startsWith("models/")) model else "models/$model"

    /**
     * Embeds a search query using `taskType = "RETRIEVAL_QUERY"` and returns an L2-normalized vector.
     */
    suspend fun embedQuery(text: String): FloatArray {
        val requestBody =
            EmbedContentRequest(
                model = modelResource,
                content = EmbedContentPayload(parts = listOf(EmbedContentPart(text = text))),
                taskType = TASK_TYPE_RETRIEVAL_QUERY,
                outputDimensionality = outputDimensionality,
            )
        val endpoint = "$normalizedBaseUrl/v1beta/$modelResource:embedContent"
        val responseText = executePost(endpoint, json.encodeToString(EmbedContentRequest.serializer(), requestBody))
        val parsed =
            runCatching { json.decodeFromString(EmbedContentResponse.serializer(), responseText) }
                .getOrElse { ex -> throw GeminiEmbeddingException("Failed to parse embedContent response", cause = ex) }
        val values =
            parsed.embedding?.values
                ?: throw GeminiEmbeddingException("Missing embedding values in embedContent response")
        return l2Normalize(values, outputDimensionality)
    }

    /**
     * Embeds a batch of document facts using `taskType = "RETRIEVAL_DOCUMENT"` and returns L2-normalized vectors.
     */
    suspend fun embedDocuments(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val requests =
            texts.map { text ->
                EmbedContentRequest(
                    model = modelResource,
                    content = EmbedContentPayload(parts = listOf(EmbedContentPart(text = text))),
                    taskType = TASK_TYPE_RETRIEVAL_DOCUMENT,
                    outputDimensionality = outputDimensionality,
                )
            }
        val endpoint = "$normalizedBaseUrl/v1beta/$modelResource:batchEmbedContents"
        val responseText =
            executePost(
                endpoint,
                json.encodeToString(BatchEmbedContentsRequest.serializer(), BatchEmbedContentsRequest(requests)),
            )
        val parsed =
            runCatching { json.decodeFromString(BatchEmbedContentsResponse.serializer(), responseText) }
                .getOrElse { ex ->
                    throw GeminiEmbeddingException("Failed to parse batchEmbedContents response", cause = ex)
                }
        if (parsed.embeddings.size != texts.size) {
            throw GeminiEmbeddingException(
                "batchEmbedContents returned ${parsed.embeddings.size} embeddings for ${texts.size} inputs",
            )
        }
        return parsed.embeddings.map { entry -> l2Normalize(entry.values, outputDimensionality) }
    }

    private suspend fun executePost(
        endpoint: String,
        payload: String,
    ): String {
        if (apiKey.isBlank()) {
            throw GeminiEmbeddingException("GEMINI_API_KEY is blank; cannot call Gemini Embedding API")
        }
        val response: HttpResponse =
            try {
                httpClient.post(endpoint) {
                    header(API_KEY_HEADER, apiKey)
                    contentType(ContentType.Application.Json)
                    applyGeminiTimeout(
                        httpClient,
                        requestTimeoutMs = timeoutMs,
                        connectTimeoutMs = timeoutMs,
                        socketTimeoutMs = timeoutMs,
                    )
                    setBody(payload)
                }
            } catch (ex: TimeoutCancellationException) {
                throw GeminiEmbeddingException("Gemini Embedding API request timed out", cause = ex)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: HttpRequestTimeoutException) {
                throw GeminiEmbeddingException("Gemini Embedding API HTTP request timed out", cause = ex)
            } catch (ex: SocketTimeoutException) {
                throw GeminiEmbeddingException("Gemini Embedding API socket timed out", cause = ex)
            } catch (ex: Exception) {
                throw GeminiEmbeddingException("Gemini Embedding API request failed: ${ex.message}", cause = ex)
            }

        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw GeminiEmbeddingException(
                "Gemini Embedding API returned HTTP ${response.status.value}: ${bodyText.take(300)}",
                statusCode = response.status.value,
            )
        }
        return bodyText
    }

    override fun close() {
        if (ownsHttpClient) {
            httpClient.close()
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-embedding-001"
        const val DEFAULT_OUTPUT_DIMENSIONALITY = 768
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val TASK_TYPE_RETRIEVAL_QUERY = "RETRIEVAL_QUERY"
        const val TASK_TYPE_RETRIEVAL_DOCUMENT = "RETRIEVAL_DOCUMENT"
        const val API_KEY_HEADER = "x-goog-api-key"

        /**
         * Validates finiteness, dimension count, and non-zero norm, returning a unit-length [FloatArray].
         */
        fun l2Normalize(
            values: List<Float>,
            expectedDimension: Int = DEFAULT_OUTPUT_DIMENSIONALITY,
        ): FloatArray {
            if (values.size != expectedDimension) {
                throw GeminiEmbeddingException(
                    "Expected $expectedDimension embedding dimensions, got ${values.size}",
                )
            }
            var sumSquares = 0.0
            for (v in values) {
                if (!v.isFinite()) {
                    throw GeminiEmbeddingException("Embedding contains non-finite value (NaN or Infinity)")
                }
                val d = v.toDouble()
                sumSquares += d * d
            }
            val norm = sqrt(sumSquares)
            if (norm <= 0.0 || !norm.isFinite()) {
                throw GeminiEmbeddingException("Embedding has zero or invalid L2 norm: $norm")
            }
            val normalized = FloatArray(values.size)
            for (i in values.indices) {
                normalized[i] = (values[i].toDouble() / norm).toFloat()
            }
            return normalized
        }
    }
}
