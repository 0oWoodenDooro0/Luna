package luna.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Shared factory for Ktor [HttpClient] instances and canonical [Json] configuration
 * used across Gemini API clients (`Interactions`, `Decisions`, `Embeddings`).
 */
object GeminiHttpClientFactory {
    const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 30_000L
    const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 10_000L
    const val DEFAULT_SOCKET_TIMEOUT_MS: Long = 30_000L

    val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    /**
     * Creates a CIO-backed [HttpClient] with Gemini [ContentNegotiation] and [HttpTimeout] defaults.
     */
    fun create(
        requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        socketTimeoutMs: Long = DEFAULT_SOCKET_TIMEOUT_MS,
        installContentNegotiation: Boolean = true,
    ): HttpClient =
        HttpClient(CIO) {
            configureGemini(
                requestTimeoutMs = requestTimeoutMs,
                connectTimeoutMs = connectTimeoutMs,
                socketTimeoutMs = socketTimeoutMs,
                installContentNegotiation = installContentNegotiation,
            )
        }

    /**
     * Creates an [HttpClient] backed by an explicit [engine] (e.g., `MockEngine` in tests)
     * with Gemini [ContentNegotiation] and [HttpTimeout] configuration.
     */
    fun create(
        engine: HttpClientEngine,
        requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
        socketTimeoutMs: Long = DEFAULT_SOCKET_TIMEOUT_MS,
        installContentNegotiation: Boolean = true,
    ): HttpClient =
        HttpClient(engine) {
            configureGemini(
                requestTimeoutMs = requestTimeoutMs,
                connectTimeoutMs = connectTimeoutMs,
                socketTimeoutMs = socketTimeoutMs,
                installContentNegotiation = installContentNegotiation,
            )
        }

    private fun HttpClientConfig<*>.configureGemini(
        requestTimeoutMs: Long,
        connectTimeoutMs: Long,
        socketTimeoutMs: Long,
        installContentNegotiation: Boolean,
    ) {
        if (installContentNegotiation) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutMs
            connectTimeoutMillis = connectTimeoutMs
            socketTimeoutMillis = socketTimeoutMs
        }
    }
}

internal fun HttpRequestBuilder.applyGeminiTimeout(
    httpClient: HttpClient,
    requestTimeoutMs: Long? = null,
    connectTimeoutMs: Long? = null,
    socketTimeoutMs: Long? = null,
) {
    if (requestTimeoutMs == null && connectTimeoutMs == null && socketTimeoutMs == null) return
    if (httpClient.pluginOrNull(HttpTimeout) == null) return
    timeout {
        requestTimeoutMs?.let { requestTimeoutMillis = it }
        connectTimeoutMs?.let { connectTimeoutMillis = it }
        socketTimeoutMs?.let { socketTimeoutMillis = it }
    }
}
