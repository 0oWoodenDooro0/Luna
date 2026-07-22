package luna.core

import com.github._0owoodendooro0.curtly.CurtlyService
import com.github._0owoodendooro0.curtly.InMemoryUrlStorage
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurtlyRoutingTest {
    @Test
    fun testGetHomePage() = testApplication {
        val storage = InMemoryUrlStorage()
        val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/")

        application {
            routing {
                curtlyRouting(curtlyService)
            }
        }

        val responseShorten = client.get("/shorten")
        assertEquals(HttpStatusCode.OK, responseShorten.status)
        assertTrue(responseShorten.bodyAsText().contains("CURTLY"))

        val responseS = client.get("/s")
        assertEquals(HttpStatusCode.OK, responseS.status)
        assertTrue(responseS.bodyAsText().contains("CURTLY"))
    }

    @Test
    fun testShortenAndResolve() = testApplication {
        val storage = InMemoryUrlStorage()
        val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/")

        application {
            routing {
                curtlyRouting(curtlyService)
            }
        }

        val customClient = createClient {
            followRedirects = false
        }

        val postResponse = customClient.post("/api/shorten") {
            contentType(ContentType.Application.Json)
            setBody("""{"longUrl": "https://example.com/test", "customKey": "testkey"}""")
        }

        assertEquals(HttpStatusCode.OK, postResponse.status)
        assertTrue(postResponse.bodyAsText().contains("http://localhost:8080/s/testkey"))

        val getResponse = customClient.get("/s/testkey")
        assertEquals(HttpStatusCode.Found, getResponse.status)
        assertEquals("https://example.com/test", getResponse.headers["Location"])
    }

    @Test
    fun testResolveNotFound() = testApplication {
        val storage = InMemoryUrlStorage()
        val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/")

        application {
            routing {
                curtlyRouting(curtlyService)
            }
        }

        val response = client.get("/s/nonexistent")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
