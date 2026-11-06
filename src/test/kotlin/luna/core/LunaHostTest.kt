package luna.core

import dev.kord.core.Kord
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
import luna.plugin.PluginLoader
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LunaHostTest {
    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:lunahost_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    @Test
    fun testEmbeddedServerApplication() {
        val server =
            embeddedServer(Netty, port = 0, host = "127.0.0.1") {
                install(Sessions) {
                    cookie<UserSession>("LUNA_SESSION")
                }
            }
        val app = server.application
        assertNotNull(app)
        val r = app.routing { }
        assertNotNull(r)
    }

    @Test
    fun testLunaHostBootstrap() =
        runTest {
            val testDb = createTestDb()
            val mockKord = mockk<Kord>(relaxed = true)
            val events = mutableListOf<String>()

            val testPlugin =
                object : LunaPlugin {
                    override val name: String = "test-plugin"

                    override suspend fun initialize(context: PluginContext) {
                        events.add("init")
                        assertNotNull(context.kord)
                        assertNotNull(context.routing)
                        assertNotNull(context.database)
                    }

                    override suspend fun start() {
                        events.add("start")
                    }

                    override suspend fun shutdown() {
                        events.add("shutdown")
                    }
                }

            val loader = PluginLoader(plugins = listOf(testPlugin))
            val host =
                LunaHost(
                    customConfig =
                        mapOf(
                            "PORT" to 8080,
                            "DISCORD_TOKEN" to "dummy_token",
                            "DISCORD_CLIENT_ID" to "dummy_client_id",
                            "DISCORD_CLIENT_SECRET" to "dummy_client_secret",
                        ),
                    customDatabase = testDb,
                    pluginLoader = loader,
                    kordFactory = { mockKord },
                )

            val context = host.start(loginKord = false)
            assertNotNull(context)
            assertEquals(listOf("init", "start"), events)
            assertNotNull(host.server)
            assertNotNull(host.kord)

            host.pluginLoader.shutdownPlugins()
            assertEquals(listOf("init", "start", "shutdown"), events)

            try {
                host.server?.stop(50, 100)
            } catch (_: Exception) {
            }
        }

    @Test
    fun testConfigLoading() {
        val config = LunaHost.loadConfig()
        assertNotNull(config)
        // Verify application.yml flattened keys exist
        assertTrue(config.containsKey("curtly.baseUrl"))
    }
}
