package luna.core

import com.github._0owoodendooro0.curtly.CurtlyService
import com.github._0owoodendooro0.curtly.InMemoryUrlStorage
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
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurtlyRoutingTest {
    private fun createTestDb(): Database =
        Database.connect("jdbc:h2:mem:test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")

    @Test
    fun testGetHomePage() =
        testApplication {
            val storage = InMemoryUrlStorage()
            val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/")
            val userStorage = UserStorage(createTestDb())

            application {
                install(Sessions) {
                    cookie<UserSession>("LUNA_SESSION")
                }
                routing {
                    curtlyRouting(curtlyService, userStorage)
                }
            }

            val responseShorten = client.get("/shorten")
            assertEquals(HttpStatusCode.OK, responseShorten.status)
            assertTrue(responseShorten.bodyAsText().contains("Luna Curtly"))

            val responseS = client.get("/s")
            assertEquals(HttpStatusCode.OK, responseS.status)
            assertTrue(responseS.bodyAsText().contains("Luna Curtly"))
        }

    @Test
    fun testShortenAndResolve() =
        testApplication {
            val storage = InMemoryUrlStorage()
            val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/", enableAuditMode = true)
            val userStorage = UserStorage(createTestDb())

            application {
                install(Sessions) {
                    cookie<UserSession>("LUNA_SESSION")
                }
                routing {
                    curtlyRouting(curtlyService, userStorage)
                }
            }

            val customClient =
                createClient {
                    followRedirects = false
                }

            val postResponse =
                customClient.post("/api/shorten") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"longUrl": "https://example.com/test", "customKey": "testkey"}""")
                }

            assertEquals(HttpStatusCode.OK, postResponse.status)
            assertTrue(postResponse.bodyAsText().contains("http://localhost:8080/s/testkey"))

            val getResponse = customClient.get("/s/testkey")
            assertEquals(HttpStatusCode.Found, getResponse.status)
            assertEquals("https://example.com/test", getResponse.headers["Location"])

            val clickLogs = curtlyService.getClickLogs("testkey")
            assertEquals(1, clickLogs.size)
        }

    @Test
    fun testResolveNotFound() =
        testApplication {
            val storage = InMemoryUrlStorage()
            val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/", enableAuditMode = true)
            val userStorage = UserStorage(createTestDb())

            application {
                install(Sessions) {
                    cookie<UserSession>("LUNA_SESSION")
                }
                routing {
                    curtlyRouting(curtlyService, userStorage)
                }
            }

            val response = client.get("/s/nonexistent")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun testUserAuthAndDashboardFlow() =
        testApplication {
            val storage = InMemoryUrlStorage()
            val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/", enableAuditMode = true)
            val userStorage = UserStorage(createTestDb())

            application {
                install(Sessions) {
                    cookie<UserSession>("LUNA_SESSION")
                }
                routing {
                    curtlyRouting(curtlyService, userStorage)
                }
            }

            val cookieClient =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            // 1. Register User
            val regResponse =
                cookieClient.post("/api/auth/register") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"testuser","password":"password123"}""")
                }
            assertEquals(HttpStatusCode.OK, regResponse.status)

            // 2. Check /api/auth/me
            val meResponse = cookieClient.get("/api/auth/me")
            assertEquals(HttpStatusCode.OK, meResponse.status)
            assertTrue(meResponse.bodyAsText().contains("testuser"))

            // 3. Shorten URL while logged in
            val shortenResponse =
                cookieClient.post("/api/shorten") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"longUrl":"https://google.com","customKey":"mygoogle"}""")
                }
            assertEquals(HttpStatusCode.OK, shortenResponse.status)

            // 4. Fetch /api/user/urls
            val userUrlsResponse = cookieClient.get("/api/user/urls")
            assertEquals(HttpStatusCode.OK, userUrlsResponse.status)
            val userUrlsText = userUrlsResponse.bodyAsText()
            assertTrue(userUrlsText.contains("mygoogle"))
            assertTrue(userUrlsText.contains("https://google.com"))

            // 5. Fetch /api/user/urls/mygoogle/stats
            val statsResponse = cookieClient.get("/api/user/urls/mygoogle/stats")
            assertEquals(HttpStatusCode.OK, statsResponse.status)
            assertTrue(statsResponse.bodyAsText().contains("mygoogle"))

            // 6. Delete link
            val delResponse = cookieClient.delete("/api/user/urls/mygoogle")
            assertEquals(HttpStatusCode.OK, delResponse.status)

            // 7. Verify deletion
            val afterDelResponse = cookieClient.get("/api/user/urls")
            assertEquals("[]", afterDelResponse.bodyAsText())
        }

    @Test
    fun testDiscordOAuthLoginRedirect() =
        testApplication {
            val storage = InMemoryUrlStorage()
            val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/")
            val fakeDiscord = FakeDiscordOAuthClient(mockAuthUrl = "https://discord.com/oauth2/authorize?client_id=123&test=1")
            val authService = AuthService(userStorage = UserStorage(createTestDb()), discordOAuthClient = fakeDiscord)

            application {
                install(Sessions) {
                    cookie<UserSession>("LUNA_SESSION")
                }
                routing {
                    curtlyRouting(curtlyService, authService)
                }
            }

            val clientNoFollow = createClient { followRedirects = false }
            val response = clientNoFollow.get("/api/auth/discord/login")

            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("https://discord.com/oauth2/authorize?client_id=123&test=1", response.headers["Location"])
            assertEquals(1, fakeDiscord.receivedRedirectUris.size)
        }

    @Test
    fun testDiscordOAuthCallbackSuccessAndDashboard() =
        testApplication {
            val storage = InMemoryUrlStorage()
            val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/")
            val userStorage = UserStorage(createTestDb())
            val fakeDiscord =
                FakeDiscordOAuthClient(
                    mockUserProfile =
                        DiscordUserProfile(
                            id = "discord_999",
                            username = "luna_bot_dev",
                            globalName = "Luna Developer",
                        ),
                )
            val authService = AuthService(userStorage = userStorage, discordOAuthClient = fakeDiscord)

            application {
                install(Sessions) {
                    cookie<UserSession>("LUNA_SESSION")
                }
                routing {
                    curtlyRouting(curtlyService, authService)
                }
            }

            val cookieClient =
                createClient {
                    install(HttpCookies)
                    followRedirects = false
                }

            // 1. Trigger callback with authorization code
            val callbackResponse = cookieClient.get("/api/auth/discord/callback?code=mock_discord_auth_code")
            assertEquals(HttpStatusCode.Found, callbackResponse.status)
            assertEquals("/dashboard", callbackResponse.headers["Location"])
            assertEquals(listOf("mock_discord_auth_code"), fakeDiscord.receivedCodes)

            // 2. Verify /api/auth/me returns authenticated user
            val meResponse = cookieClient.get("/api/auth/me")
            assertEquals(HttpStatusCode.OK, meResponse.status)
            val meBody = meResponse.bodyAsText()
            assertTrue(meBody.contains("discord_999"))
            assertTrue(meBody.contains("Luna Developer"))

            // 3. Verify user was stored in database
            val user = userStorage.findById("discord_999")
            assertNotNull(user)
            assertEquals("Luna Developer", user.username)
        }

    @Test
    fun testDiscordOAuthCallbackErrorHandling() =
        testApplication {
            val storage = InMemoryUrlStorage()
            val curtlyService = CurtlyService(storage = storage, baseUrl = "http://localhost:8080/s/")
            val fakeDiscord =
                FakeDiscordOAuthClient(
                    shouldFail = true,
                    failureException = IllegalStateException("Discord upstream 500 error"),
                )
            val authService = AuthService(userStorage = UserStorage(createTestDb()), discordOAuthClient = fakeDiscord)

            application {
                install(Sessions) {
                    cookie<UserSession>("LUNA_SESSION")
                }
                routing {
                    curtlyRouting(curtlyService, authService)
                }
            }

            val clientNoFollow = createClient { followRedirects = false }

            // 1. Missing code parameter
            val noCodeResponse = clientNoFollow.get("/api/auth/discord/callback")
            assertEquals(HttpStatusCode.Found, noCodeResponse.status)
            assertEquals("/login?error=discord_cancel", noCodeResponse.headers["Location"])

            // 2. Exception during code exchange
            val failedCodeResponse = clientNoFollow.get("/api/auth/discord/callback?code=bad_code")
            assertEquals(HttpStatusCode.Found, failedCodeResponse.status)
            val location = failedCodeResponse.headers["Location"] ?: ""
            assertTrue(location.startsWith("/login?error="))
            assertTrue(location.contains("Discord"))
        }

    @Test
    fun testAuthServiceDirectUnit() {
        val userStorage = UserStorage(createTestDb())
        val fakeDiscord =
            FakeDiscordOAuthClient(
                mockUserProfile =
                    DiscordUserProfile(
                        id = "u_888",
                        username = "alice",
                        globalName = null,
                    ),
            )
        val authService = AuthService(userStorage, fakeDiscord)

        // Test normal registration and login
        val registered = authService.register("bob123", "password123")
        assertEquals("bob123", registered.username)

        val authenticated = authService.authenticate("bob123", "password123")
        assertNotNull(authenticated)
        assertEquals("bob123", authenticated.username)

        val wrongAuth = authService.authenticate("bob123", "wrong_password")
        assertNull(wrongAuth)

        // Test findById
        val found = authService.findById(registered.userId)
        assertNotNull(found)
        assertEquals("bob123", found.username)
    }
}
