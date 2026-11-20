package luna.chatter.memory

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import luna.chatter.ChatterNoiseFilter
import luna.core.LunaHost
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryDistillationCoordinatorTest {
    @TempDir
    lateinit var tempDir: Path

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant(): Instant = current

        fun advanceMinutes(minutes: Long) {
            current = current.plusSeconds(minutes * 60)
        }
    }

    private fun createStore(clock: Clock): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("coord-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock)
    }

    private fun mockMessage(
        id: Long,
        channelId: Long = 1000L,
        authorId: Long = 111L,
        username: String = "Alice",
        content: String = "hello",
        isBot: Boolean = false,
        type: MessageType = MessageType.Default,
    ): Message {
        val user =
            mockk<User>(relaxed = true) {
                every { this@mockk.id } returns Snowflake(authorId)
                every { this@mockk.username } returns username
                every { this@mockk.isBot } returns isBot
            }
        return mockk<Message>(relaxed = true) {
            every { this@mockk.id } returns Snowflake(id)
            every { this@mockk.channelId } returns Snowflake(channelId)
            every { this@mockk.author } returns user
            every { this@mockk.content } returns content
            every { this@mockk.type } returns type
            every { webhookId } returns null
            every { attachments } returns emptySet()
            every { embeds } returns emptyList()
            every { mentionedUserIds } returns emptySet()
        }
    }

    @Test
    fun `cold start writes cursor at first observed message without triggering distillation`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            var distillCalls = 0
            val coordinator =
                MemoryDistillationCoordinator(
                    memoryStore = store,
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    clock = clock,
                    distillExecutor = { _, _, _ ->
                        distillCalls++
                        DistillOutcome.Completed(0, "100")
                    },
                )

            coordinator.observeMessage(
                message = mockMessage(id = 100L, content = "first message"),
                guildId = "2000",
            )

            val cursor = store.getCursor("1000")
            assertNotNull(cursor)
            assertEquals("100", cursor.lastSummarizedMessageId)
            assertEquals("2000", cursor.guildId)

            clock.advanceMinutes(60)
            coordinator.tick()
            assertEquals(0, distillCalls)
        }

    @Test
    fun `ignores non-allowed channels and channels flooded only by bots`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            var distillCalls = 0
            val coordinator =
                MemoryDistillationCoordinator(
                    memoryStore = store,
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(quietMinutes = 5, minMessages = 5),
                    allowedChannels = setOf(Snowflake(1000L)),
                    clock = clock,
                    distillExecutor = { _, _, _ ->
                        distillCalls++
                        DistillOutcome.Completed(0, "999")
                    },
                )

            // Non-allowed channel 9999 should be completely ignored (no cursor written)
            coordinator.observeMessage(mockMessage(id = 10L, channelId = 9999L), guildId = "2000")
            assertNull(store.getCursor("9999"))

            // Allowed channel 1000 cold start
            coordinator.observeMessage(mockMessage(id = 100L, channelId = 1000L), guildId = "2000")
            // Flood with 10 third-party bot messages + 4 human messages (< minMessages 5)
            repeat(10) { i ->
                coordinator.observeMessage(
                    mockMessage(id = 101L + i, channelId = 1000L, authorId = 888L, isBot = true, content = "bot msg $i"),
                    guildId = "2000",
                )
            }
            repeat(4) { i ->
                coordinator.observeMessage(
                    mockMessage(id = 200L + i, channelId = 1000L, authorId = 111L, isBot = false, content = "human msg $i"),
                    guildId = "2000",
                )
            }

            clock.advanceMinutes(40)
            coordinator.tick()
            assertEquals(0, distillCalls)
        }

    @Test
    fun `quiet trigger fires after 5m quiet with at least 5 human messages`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor("1000", "2000", "100")
            val distilled = mutableListOf<Triple<String, String, String>>()
            val coordinator =
                MemoryDistillationCoordinator(
                    memoryStore = store,
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(quietMinutes = 5, minMessages = 5),
                    clock = clock,
                    distillExecutor = { ch, g, cur ->
                        distilled.add(Triple(ch, g, cur))
                        store.upsertCursor(ch, g, "105")
                        DistillOutcome.Completed(1, "105")
                    },
                )

            repeat(5) { i ->
                coordinator.observeMessage(mockMessage(id = 101L + i), guildId = "2000")
            }

            // Before 5m quiet: should NOT trigger
            clock.advanceMinutes(4)
            coordinator.tick()
            assertTrue(distilled.isEmpty(), "Should not trigger before 5m quiet")

            // At 5m quiet: should trigger
            clock.advanceMinutes(1)
            coordinator.tick()
            assertEquals(listOf(Triple("1000", "2000", "100")), distilled)

            // Next tick should NOT re-trigger with 0 new messages
            clock.advanceMinutes(10)
            coordinator.tick()
            assertEquals(1, distilled.size)
        }

    @Test
    fun `forced triggers fire at maxBatchMessages or maxBatchAgeMinutes without quiet gap`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor("1000", "2000", "100")
            store.upsertCursor("1001", "2000", "200")

            val distilledChannels = mutableListOf<String>()
            val coordinator =
                MemoryDistillationCoordinator(
                    memoryStore = store,
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(quietMinutes = 5, minMessages = 5, maxBatchMessages = 50, maxBatchAgeMinutes = 30),
                    clock = clock,
                    distillExecutor = { ch, g, _ ->
                        distilledChannels.add(ch)
                        store.upsertCursor(ch, g, "999")
                        DistillOutcome.Completed(1, "999")
                    },
                )

            // Channel 1000: 50 messages with 0 quiet gap
            repeat(50) { i ->
                coordinator.observeMessage(mockMessage(id = 101L + i, channelId = 1000L), guildId = "2000")
            }

            // Channel 1001: 5 messages spread over 30m with recent activity (no 5m quiet gap)
            repeat(4) { i ->
                coordinator.observeMessage(mockMessage(id = 201L + i, channelId = 1001L), guildId = "2000")
            }
            clock.advanceMinutes(30)
            coordinator.observeMessage(mockMessage(id = 205L, channelId = 1001L), guildId = "2000")

            coordinator.tick()
            assertEquals(listOf("1000", "1001"), distilledChannels)
        }

    @Test
    fun `transient failure evicts inFlight state and leaves cursor unchanged so next tick retries`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            store.upsertCursor("1000", "2000", "100")
            var attempts = 0
            val coordinator =
                MemoryDistillationCoordinator(
                    memoryStore = store,
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(quietMinutes = 5, minMessages = 5),
                    clock = clock,
                    distillExecutor = { ch, g, _ ->
                        attempts++
                        if (attempts == 1) {
                            DistillOutcome.Failed("HTTP 429")
                        } else {
                            store.upsertCursor(ch, g, "105")
                            DistillOutcome.Completed(1, "105")
                        }
                    },
                )

            repeat(5) { i ->
                coordinator.observeMessage(mockMessage(id = 101L + i), guildId = "2000")
            }
            clock.advanceMinutes(5)

            // First tick fails transiently
            coordinator.tick()
            assertEquals(1, attempts)
            assertEquals("100", store.getCursor("1000")?.lastSummarizedMessageId)

            // Next tick retries cleanly and succeeds
            coordinator.tick()
            assertEquals(2, attempts)
            assertEquals("105", store.getCursor("1000")?.lastSummarizedMessageId)
        }

    @Test
    fun `LRU activity tracker bounds capacity to maxEntries`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
            val store = createStore(clock)
            val coordinator =
                MemoryDistillationCoordinator(
                    memoryStore = store,
                    noiseFilter = ChatterNoiseFilter(),
                    config = ChatterMemoryConfig(),
                    clock = clock,
                    maxTrackedChannels = 3,
                    distillExecutor = { _, _, _ -> DistillOutcome.NoMessages },
                )

            for (ch in 1L..5L) {
                coordinator.observeMessage(mockMessage(id = ch * 10, channelId = ch), guildId = "2000")
            }

            assertEquals(3, coordinator.trackedChannelCount())
        }
}
