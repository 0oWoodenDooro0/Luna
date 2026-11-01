package luna.plugin

import dev.kord.core.Kord
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginIntegrationTest {
    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:plugin_it_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    @BeforeEach
    fun setUp() {
        TestLunaPlugin.reset()
    }

    @Test
    fun `plugin loader discovers and manages lifecycle of test plugin via service loader`() =
        testApplication {
            val loader = PluginLoader()
            val discoveredPlugins = loader.loadPlugins()

            assertTrue(
                discoveredPlugins.any { it is TestLunaPlugin },
                "Expected TestLunaPlugin to be discovered by ServiceLoader",
            )

            val mockKord = mockk<Kord>(relaxed = true)
            val testDb = createTestDb()

            assertFalse(TestLunaPlugin.initialized)
            assertFalse(TestLunaPlugin.started)
            assertFalse(TestLunaPlugin.shutdown)

            application {
                routing {
                    val context =
                        DefaultPluginContext(
                            kord = mockKord,
                            routing = this,
                            database = testDb,
                            configMap = mapOf("test.greeting" to "Integration Greetings from Config"),
                        )

                    runBlocking {
                        loader.initializePlugins(context)
                        loader.startPlugins()
                    }
                }
            }

            startApplication()

            assertTrue(TestLunaPlugin.initialized)
            assertTrue(TestLunaPlugin.started)
            assertEquals("Integration Greetings from Config", TestLunaPlugin.recordedConfigValue)

            // Verify Ktor routing configured by plugin
            val response = client.get("/api/test-plugin/hello")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Integration Greetings from Config", response.bodyAsText())

            // Verify Database interaction configured by plugin
            transaction(testDb) {
                val items = TestPluginItemsTable.selectAll().toList()
                assertEquals(1, items.size)
                assertEquals("item-1", items.first()[TestPluginItemsTable.id])
                assertEquals("Test Item", items.first()[TestPluginItemsTable.name])
            }

            // Verify graceful shutdown
            runBlocking {
                loader.shutdownPlugins()
            }
            assertTrue(TestLunaPlugin.shutdown)
        }
}
