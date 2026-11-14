package luna.curtly

import dev.kord.core.Kord
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import luna.curtly.engine.ClickLogsEntries
import luna.curtly.engine.UrlEntries
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurtlyPluginTest {
    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:curtly_plugin_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    @Test
    fun `curtly plugin initializes database tables, registers commands and mounts routes`() =
        testApplication {
            val mockKord = mockk<Kord>(relaxed = true)
            val testDb = createTestDb()
            val plugin = CurtlyPlugin()

            application {
                routing {
                    val context =
                        DefaultPluginContext(
                            kord = mockKord,
                            routing = this,
                            database = testDb,
                            configMap = mapOf("curtly.baseUrl" to "https://short.test/s/"),
                        )

                    runBlocking {
                        plugin.initialize(context)
                        plugin.start()
                    }
                }
            }

            startApplication()

            // Verify database tables were created and operational
            transaction(testDb) {
                val urls = UrlEntries.selectAll().toList()
                val clickLogs = ClickLogsEntries.selectAll().toList()
                assertEquals(0, urls.size)
                assertEquals(0, clickLogs.size)
            }

            // Verify Ktor routing mounted
            val shortenPageRes = client.get("/shorten")
            assertEquals(HttpStatusCode.OK, shortenPageRes.status)
            assertTrue(shortenPageRes.bodyAsText().contains("Luna Curtly"))

            val shortenApiRes =
                client.post("/api/shorten") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"longUrl":"https://kotlinlang.org","customKey":"kt-link"}""")
                }
            assertEquals(HttpStatusCode.OK, shortenApiRes.status)
            assertTrue(shortenApiRes.bodyAsText().contains("https://short.test/s/kt-link"))

            val noFollowClient = createClient { followRedirects = false }
            val redirectRes = noFollowClient.get("/s/kt-link")
            assertEquals(HttpStatusCode.Found, redirectRes.status)
            assertEquals("https://kotlinlang.org", redirectRes.headers["Location"])

            runBlocking {
                plugin.shutdown()
            }
        }

    @Test
    fun `curtly plugin configures oauth client and redirectUri from PluginContext`() =
        testApplication {
            val mockKord = mockk<Kord>(relaxed = true)
            val testDb = createTestDb()
            val plugin = CurtlyPlugin()

            application {
                routing {
                    val context =
                        DefaultPluginContext(
                            kord = mockKord,
                            routing = this,
                            database = testDb,
                            configMap =
                                mapOf(
                                    "curtly.baseUrl" to "https://short.test/s/",
                                    "DISCORD_CLIENT_ID" to "test-client-id-123",
                                    "DISCORD_CLIENT_SECRET" to "test-client-secret-456",
                                    "DISCORD_REDIRECT_URI" to "https://oauth.test/callback",
                                ),
                        )

                    runBlocking {
                        plugin.initialize(context)
                    }
                }
            }

            startApplication()

            val noFollowClient = createClient { followRedirects = false }
            val loginRes = noFollowClient.get("/api/auth/discord/login")
            assertEquals(HttpStatusCode.Found, loginRes.status)
            val location = loginRes.headers["Location"]
            assertNotNull(location)
            assertTrue(location.contains("client_id=test-client-id-123"))
            assertTrue(location.contains("redirect_uri=https%3A%2F%2Foauth.test%2Fcallback"))
        }
}
