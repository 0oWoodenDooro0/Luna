package luna.curtly.adapter

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.testing.testApplication
import luna.core.AuthService
import luna.core.UserSession
import luna.core.UserStorage
import luna.curtly.engine.CurtlyService
import luna.curtly.engine.InMemoryUrlStorage
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurtlyAdapterTest {
    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:adapter_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    @Test
    fun `commands have correct slash command names and descriptions`() {
        val service = CurtlyService(InMemoryUrlStorage())
        val shortenCmd = ShortenCommand(service)
        val myUrlsCmd = MyUrlsCommand(service, "http://localhost:8080/s/")

        assertEquals("shorten", shortenCmd.name)
        assertEquals("myurls", myUrlsCmd.name)
        assertTrue(shortenCmd.description.isNotBlank())
        assertTrue(myUrlsCmd.description.isNotBlank())
    }

    @Test
    fun `curtly routing mounts full HTTP suite and manages short urls and analytics`() =
        testApplication {
            val storage = InMemoryUrlStorage()
            val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/", enableAuditMode = true)
            val authService = AuthService(UserStorage(createTestDb()))

            application {
                install(Sessions) {
                    cookie<UserSession>("LUNA_SESSION")
                }
                routing {
                    curtlyRouting(curtlyService, authService, "http://localhost:8080/s/")
                }
            }

            val clientNoFollow = createClient { followRedirects = false }
            val cookieClient =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            // Public shorten page
            val shortenPageRes = clientNoFollow.get("/shorten")
            assertEquals(HttpStatusCode.OK, shortenPageRes.status)
            assertTrue(shortenPageRes.bodyAsText().contains("Luna Curtly"))

            // Shorten API
            val shortenApiRes =
                clientNoFollow.post("/api/shorten") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"longUrl": "https://example.com/target", "customKey": "custom-link"}""")
                }
            assertEquals(HttpStatusCode.OK, shortenApiRes.status)
            assertTrue(shortenApiRes.bodyAsText().contains("http://localhost:8080/s/custom-link"))

            // Redirect
            val redirectRes = clientNoFollow.get("/s/custom-link")
            assertEquals(HttpStatusCode.Found, redirectRes.status)
            assertEquals("https://example.com/target", redirectRes.headers["Location"])

            // Register user & authenticated actions
            val registerRes =
                cookieClient.post("/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"adapter_user","password":"secure_password_123"}""")
                }
            assertEquals(HttpStatusCode.OK, registerRes.status)

            // Shorten URL with user session
            val userShortenRes =
                cookieClient.post("/api/shorten") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"longUrl":"https://example.org/dashboard-link","customKey":"user-link"}""")
                }
            assertEquals(HttpStatusCode.OK, userShortenRes.status)

            // User urls list
            val userUrlsRes = cookieClient.get("/api/user/urls")
            assertEquals(HttpStatusCode.OK, userUrlsRes.status)
            assertTrue(userUrlsRes.bodyAsText().contains("user-link"))

            // User url stats
            val statsRes = cookieClient.get("/api/user/urls/user-link/stats")
            assertEquals(HttpStatusCode.OK, statsRes.status)
            assertTrue(statsRes.bodyAsText().contains("user-link"))

            // Delete user url
            val delRes = cookieClient.delete("/api/user/urls/user-link")
            assertEquals(HttpStatusCode.OK, delRes.status)

            val emptyUrlsRes = cookieClient.get("/api/user/urls")
            assertEquals("[]", emptyUrlsRes.bodyAsText())
        }
}
