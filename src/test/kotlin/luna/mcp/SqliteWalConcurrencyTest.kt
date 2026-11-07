package luna.mcp

import luna.core.LunaHost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteWalConcurrencyTest {
    private lateinit var tempDbFile: File

    @BeforeEach
    fun setUp() {
        tempDbFile = File.createTempFile("luna_app_wal_test_", ".db")
    }

    @AfterEach
    fun tearDown() {
        tempDbFile.delete()
        File("${tempDbFile.absolutePath}-wal").delete()
        File("${tempDbFile.absolutePath}-shm").delete()
    }

    @Test
    fun `LunaHost initDatabase and McpWebhookStorage initialize with WAL mode and 5000ms busy timeout`() {
        val hostDb = LunaHost.initDatabase(tempDbFile.absolutePath)
        val storage = McpWebhookStorage(hostDb)

        val conn = DriverManager.getConnection("jdbc:sqlite:${tempDbFile.absolutePath}")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL;")
            stmt.execute("PRAGMA busy_timeout=5000;")
        }

        conn.createStatement().use { stmt ->
            val journalRs = stmt.executeQuery("PRAGMA journal_mode;")
            assertTrue(journalRs.next())
            assertEquals("wal", journalRs.getString(1).lowercase())

            val busyRs = stmt.executeQuery("PRAGMA busy_timeout;")
            assertTrue(busyRs.next())
            assertEquals(5000, busyRs.getInt(1))
        }
        conn.close()

        assertTrue(storage.getAllWebhooks().isEmpty())
    }

    @Test
    fun `simulated multi-process concurrent reads and writes in app complete without locking errors`() {
        // Process 1 (Luna Bot) connection
        val botDb = LunaHost.initDatabase(tempDbFile.absolutePath)
        val botClientRepo = McpClientRepository(botDb)
        val botWebhookRepo = McpWebhookRepository(botDb)

        // Process 2 (MCP Server) independent connection
        val mcpDb = LunaHost.initDatabase(tempDbFile.absolutePath)
        val mcpClientRepo = McpClientRepository(mcpDb)
        val mcpWebhookRepo = McpWebhookRepository(mcpDb)

        val threadCount = 4
        val iterationsPerThread = 25
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            val tasks =
                (0 until threadCount).map { threadIndex ->
                    Callable {
                        val clientRepo = if (threadIndex % 2 == 0) botClientRepo else mcpClientRepo
                        val webhookRepo = if (threadIndex % 2 == 0) botWebhookRepo else mcpWebhookRepo

                        for (i in 0 until iterationsPerThread) {
                            val clientId = "app-client-$threadIndex-$i"
                            val userId = "app-user-$threadIndex-$i"
                            val now = System.currentTimeMillis()

                            // 1. Write client record
                            clientRepo.createClient(
                                McpClientRecord(
                                    clientId = clientId,
                                    userId = userId,
                                    clientName = "App Client $threadIndex-$i",
                                    secretHash = "hash-$i",
                                    secretSalt = "salt-$i",
                                    createdAt = now,
                                    updatedAt = now,
                                ),
                            )

                            // 2. Write webhook record
                            webhookRepo.addWebhook(
                                McpWebhookRecord(
                                    id = "wh-app-$threadIndex-$i",
                                    clientId = clientId,
                                    tag = "tag-$i",
                                    guildId = "guild-app",
                                    channelId = "channel-app",
                                    webhookUrl = "https://discord.com/api/webhooks/app/$threadIndex/$i",
                                    isAutoProvisioned = true,
                                    createdAt = now,
                                ),
                            )

                            // 3. Read back client and webhook
                            val foundClient = clientRepo.findByClientId(clientId)
                            checkNotNull(foundClient) { "App client $clientId should exist" }

                            val foundWebhook = webhookRepo.findByClientAndTag(clientId, "tag-$i")
                            checkNotNull(foundWebhook) { "App webhook $clientId:tag-$i should exist" }

                            // 4. Update secret
                            clientRepo.updateSecret(clientId, "updated-hash-$i", "updated-salt-$i", now + 15)
                        }
                        true
                    }
                }

            val futures = executor.invokeAll(tasks)
            for (future in futures) {
                assertTrue(future.get(10, TimeUnit.SECONDS), "Thread execution must succeed")
            }

            val all = mcpWebhookRepo.getAllWebhooks()
            assertEquals(threadCount * iterationsPerThread, all.size)
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
