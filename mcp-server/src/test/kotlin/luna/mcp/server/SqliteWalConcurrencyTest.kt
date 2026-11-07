package luna.mcp.server

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
        tempDbFile = File.createTempFile("mcp_server_wal_test_", ".db")
    }

    @AfterEach
    fun tearDown() {
        tempDbFile.delete()
        File("${tempDbFile.absolutePath}-wal").delete()
        File("${tempDbFile.absolutePath}-shm").delete()
    }

    @Test
    fun `SQLite database connection initializes with WAL journal mode and 5000ms busy timeout`() {
        val repo = McpWebhookRepository.create(tempDbFile.absolutePath)
        val clientRepo = McpClientRepository.create(tempDbFile.absolutePath)

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

        val webhooks = repo.getAllWebhooks()
        assertTrue(webhooks.isEmpty())

        val client = clientRepo.findByClientId("none")
        assertEquals(null, client)
    }

    @Test
    fun `simulated multi-process concurrent reads and writes complete without locking errors`() {
        // Process 1 (e.g. Luna Bot) connection and repositories
        val process1WebhookRepo = McpWebhookRepository.create(tempDbFile.absolutePath)
        val process1ClientRepo = McpClientRepository.create(tempDbFile.absolutePath)

        // Process 2 (e.g. MCP Server) independent connection and repositories
        val process2WebhookRepo = McpWebhookRepository.create(tempDbFile.absolutePath)
        val process2ClientRepo = McpClientRepository.create(tempDbFile.absolutePath)

        val threadCount = 4
        val iterationsPerThread = 25
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            val tasks =
                (0 until threadCount).map { threadIndex ->
                    Callable {
                        val clientRepo = if (threadIndex % 2 == 0) process1ClientRepo else process2ClientRepo
                        val webhookRepo = if (threadIndex % 2 == 0) process1WebhookRepo else process2WebhookRepo

                        for (i in 0 until iterationsPerThread) {
                            val clientId = "client-$threadIndex-$i"
                            val userId = "user-$threadIndex-$i"
                            val now = System.currentTimeMillis()

                            // 1. Write client record
                            clientRepo.createClient(
                                McpClientRecord(
                                    clientId = clientId,
                                    userId = userId,
                                    clientName = "Client $threadIndex-$i",
                                    secretHash = "hash-$i",
                                    secretSalt = "salt-$i",
                                    createdAt = now,
                                    updatedAt = now,
                                ),
                            )

                            // 2. Write webhook record
                            webhookRepo.addWebhook(
                                McpWebhookRecord(
                                    id = "wh-$threadIndex-$i",
                                    clientId = clientId,
                                    tag = "tag-$i",
                                    guildId = "guild-1",
                                    channelId = "channel-1",
                                    webhookUrl = "https://discord.com/api/webhooks/$threadIndex/$i",
                                    isAutoProvisioned = true,
                                    createdAt = now,
                                ),
                            )

                            // 3. Read back client and webhook
                            val foundClient = clientRepo.findByClientId(clientId)
                            checkNotNull(foundClient) { "Client $clientId should exist" }

                            val foundWebhook = webhookRepo.findByClientAndTag(clientId, "tag-$i")
                            checkNotNull(foundWebhook) { "Webhook $clientId:tag-$i should exist" }

                            // 4. Update secret
                            clientRepo.updateSecret(clientId, "new-hash-$i", "new-salt-$i", now + 10)
                        }
                        true
                    }
                }

            val futures = executor.invokeAll(tasks)
            for (future in futures) {
                assertTrue(future.get(10, TimeUnit.SECONDS), "Thread execution must succeed")
            }

            val allWebhooks = process2WebhookRepo.getAllWebhooks()
            assertEquals(threadCount * iterationsPerThread, allWebhooks.size)
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
