package dev.socialpeek.network

import dev.socialpeek.exception.PostNotFoundException
import dev.socialpeek.exception.RateLimitedException
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

interface SocialPeekHttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String
    suspend fun resolveFinalUrl(url: String, headers: Map<String, String> = emptyMap()): String
}

class KtorSocialPeekHttpClient(
    engine: HttpClientEngine = CIO.create(),
    private val defaultUserAgent: String = DEFAULT_USER_AGENT
) : SocialPeekHttpClient, AutoCloseable {

    companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        const val BOT_USER_AGENT = "Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)"
        const val META_USER_AGENT = "facebookexternalhit/1.1"
    }

    val ktor = HttpClient(engine) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 15000
        }
        followRedirects = true
    }

    override suspend fun get(url: String, headers: Map<String, String>): String {
        val response = ktor.get(url) {
            headers {
                if (!headers.containsKey(HttpHeaders.UserAgent)) {
                    append(HttpHeaders.UserAgent, defaultUserAgent)
                }
                headers.forEach { (k, v) -> append(k, v) }
            }
        }

        if (response.status == HttpStatusCode.NotFound) {
            throw PostNotFoundException(url, "HTTP 404 Not Found")
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw RateLimitedException(url, response.request.url.host)
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("HTTP ${response.status.value}: ${response.bodyAsText()}")
        }

        return response.bodyAsText()
    }

    override suspend fun resolveFinalUrl(url: String, headers: Map<String, String>): String {
        val response = ktor.get(url) {
            headers {
                if (!headers.containsKey(HttpHeaders.UserAgent)) {
                    append(HttpHeaders.UserAgent, defaultUserAgent)
                }
                headers.forEach { (k, v) -> append(k, v) }
            }
        }
        return response.request.url.toString()
    }

    override fun close() {
        ktor.close()
    }
}
