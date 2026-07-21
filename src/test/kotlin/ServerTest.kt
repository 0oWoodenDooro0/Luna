package com.github._0owoodendooro0

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.*
import java.util.UUID

class ServerTest {

    @Test
    fun `test root endpoint`() = testApplication {
        configure()
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

    @Test
    fun `test shorten and redirect`() = testApplication {
        configure()
        
        val testClient = createClient {
            followRedirects = false
        }
        
        // Use a unique key to prevent 409 Conflict from previous test runs on local disk
        val uniqueKey = "git-" + UUID.randomUUID().toString().take(6)
        
        val shortenResponse = testClient.post("/shorten") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://github.com","key":"$uniqueKey"}""")
        }
        
        assertEquals(HttpStatusCode.Created, shortenResponse.status)
        val responseBody = shortenResponse.bodyAsText()
        assertTrue(responseBody.contains("http://localhost:8080/$uniqueKey"), "Response body was: $responseBody")
        
        val redirectResponse = testClient.get("/$uniqueKey")
        assertEquals(HttpStatusCode.Found, redirectResponse.status)
        assertEquals("https://github.com", redirectResponse.headers[HttpHeaders.Location])
    }

    @Test
    fun `test custom baseUrl from config`() = testApplication {
        System.setProperty("curtly.baseUrl", "https://mycustomdomain.com")
        try {
            configure()
            
            val testClient = createClient {
                followRedirects = false
            }
            
            val uniqueKey = "custom-" + UUID.randomUUID().toString().take(6)
            
            val shortenResponse = testClient.post("/shorten") {
                contentType(ContentType.Application.Json)
                setBody("""{"url":"https://github.com","key":"$uniqueKey"}""")
            }
            
            assertEquals(HttpStatusCode.Created, shortenResponse.status)
            val responseBody = shortenResponse.bodyAsText()
            assertTrue(responseBody.contains("https://mycustomdomain.com/$uniqueKey"), "Response body was: $responseBody")
        } finally {
            System.clearProperty("curtly.baseUrl")
        }
    }

    @Test
    fun `test custom baseUrl from MapApplicationConfig`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "curtly.baseUrl" to "https://mycustomdomain2.com"
            )
        }
        application {
            configureSerialization()
            configureRouting()
        }

        
        val testClient = createClient {
            followRedirects = false
        }
        
        val uniqueKey = "custom2-" + UUID.randomUUID().toString().take(6)
        
        val shortenResponse = testClient.post("/shorten") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://github.com","key":"$uniqueKey"}""")
        }
        
        assertEquals(HttpStatusCode.Created, shortenResponse.status)
        val responseBody = shortenResponse.bodyAsText()
        assertTrue(responseBody.contains("https://mycustomdomain2.com/$uniqueKey"), "Response body was: $responseBody")
    }

    @Test
    fun `test shorten with maxClicks and expiration`() = testApplication {
        configure()

        val testClient = createClient {
            followRedirects = false
        }

        val uniqueKey = "limit-" + UUID.randomUUID().toString().take(6)

        val shortenResponse = testClient.post("/shorten") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://github.com","key":"$uniqueKey","maxClicks":1}""")
        }

        assertEquals(HttpStatusCode.Created, shortenResponse.status)

        // 1st click - success redirect
        val redirectResponse1 = testClient.get("/$uniqueKey")
        assertEquals(HttpStatusCode.Found, redirectResponse1.status)

        // 2nd click - max clicks reached, return 404
        val redirectResponse2 = testClient.get("/$uniqueKey")
        assertEquals(HttpStatusCode.NotFound, redirectResponse2.status)
    }
}



