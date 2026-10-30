package dev.socialpeek.test

import dev.socialpeek.network.KtorSocialPeekHttpClient
import dev.socialpeek.network.SocialPeekHttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*

fun createMockHttpClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SocialPeekHttpClient {
    val mockEngine = MockEngine { request ->
        handler(request)
    }
    return KtorSocialPeekHttpClient(engine = mockEngine)
}

fun MockRequestHandleScope.jsonResponse(content: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData {
    return respond(
        content = content,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8")
    )
}

fun MockRequestHandleScope.htmlResponse(content: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData {
    return respond(
        content = content,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
    )
}
