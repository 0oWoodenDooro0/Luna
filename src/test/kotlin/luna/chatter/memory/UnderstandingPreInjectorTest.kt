package luna.chatter.memory

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import luna.core.LunaHost
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnderstandingPreInjectorTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createStore(clock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)): SqliteChatterMemoryStore {
        val dbPath = tempDir.resolve("pre-injector-${System.nanoTime()}.db").toString()
        val db = LunaHost.initDatabase(dbPath = dbPath, vecExtensionPath = tempDir.resolve("missing-vec0.so").toString())
        return SqliteChatterMemoryStore(database = db, clock = clock)
    }

    @Test
    fun `pre-injects author up to 10, up to 2 other recent human speakers up to 5 each, and guild up to 8 while touching accessed ids`() {
        val mutableClock =
            object : Clock() {
                var now: Instant = Instant.parse("2026-01-01T00:00:00Z")

                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: java.time.ZoneId) = this

                override fun instant(): Instant = now
            }
        val store = createStore(mutableClock)

        // Author 101: 12 entries -> expect max 10
        repeat(12) { idx ->
            store.insertUnderstanding("1001", MemoryScope.USER, "101", "author_s$idx", "a$idx")
        }
        // Other speaker 202 (recent): 6 entries -> expect max 5
        repeat(6) { idx ->
            store.insertUnderstanding("1001", MemoryScope.USER, "202", "b_s$idx", "b$idx")
        }
        // Other speaker 303 (recent): 6 entries -> expect max 5
        repeat(6) { idx ->
            store.insertUnderstanding("1001", MemoryScope.USER, "303", "c_s$idx", "c$idx")
        }
        // Older speaker 404 (3rd other speaker): should NOT be included (cap 2 other speakers)
        store.insertUnderstanding("1001", MemoryScope.USER, "404", "old_speaker", "should not inject")
        // Bot speaker 505 and Luna 606: should NOT be included
        store.insertUnderstanding("1001", MemoryScope.USER, "505", "bot_speaker", "bot should not inject")
        store.insertUnderstanding("1001", MemoryScope.USER, "606", "luna_speaker", "luna should not inject")
        // Guild: 10 entries -> expect max 8
        repeat(10) { idx ->
            store.insertUnderstanding("1001", MemoryScope.GUILD, null, "guild_s$idx", "g$idx")
        }

        mutableClock.now = Instant.parse("2026-01-02T00:00:00Z")

        val transcript =
            """
            Dave (uid:404): 很久以前的話
            Helper [BOT] (uid:505): 機器人訊息
            Luna (uid:606): 我剛才說過了
            Bob (uid:202): 哈囉
            Charlie (uid:303): 安安
            Alice (uid:101): 大家今天好嗎
            """.trimIndent()

        val injector = UnderstandingPreInjector(memoryStore = store, maxChars = 4000)
        val section = injector.buildPreInjectionSection(guildId = "1001", authorId = "101", transcript = transcript)

        assertNotNull(section)
        assertTrue(section.contains("uid:101"), "Expected triggering author uid:101 in section")
        assertTrue(section.contains("uid:202"), "Expected recent speaker uid:202 in section")
        assertTrue(section.contains("uid:303"), "Expected recent speaker uid:303 in section")
        assertFalse(section.contains("should not inject"), "Expected 3rd older speaker to be excluded")
        assertFalse(section.contains("bot should not inject"), "Expected [BOT] speaker to be excluded")
        assertFalse(section.contains("luna should not inject"), "Expected Luna speaker to be excluded")

        val authorLines = section.lines().count { it.startsWith("- author_s") }
        val bobLines = section.lines().count { it.startsWith("- b_s") }
        val charlieLines = section.lines().count { it.startsWith("- c_s") }
        val guildLines = section.lines().count { it.startsWith("- guild_s") }
        assertEquals(10, authorLines)
        assertEquals(5, bobLines)
        assertEquals(5, charlieLines)
        assertEquals(8, guildLines)

        val touchedAtMs = mutableClock.instant().toEpochMilli()
        val authorTouchedCount = store.listUserUnderstandings("1001", "101").count { it.lastAccessedAt == touchedAtMs }
        val guildTouchedCount = store.listGuildUnderstandings("1001").count { it.lastAccessedAt == touchedAtMs }
        assertEquals(10, authorTouchedCount)
        assertEquals(8, guildTouchedCount)
    }

    @Test
    fun `truncates entries cleanly at soft character budget without exceeding maxChars`() {
        val store = createStore()
        repeat(8) { idx ->
            store.insertUnderstanding(
                guildId = "1001",
                scope = MemoryScope.USER,
                targetUserId = "101",
                subject = "習慣$idx",
                content = "這是一段比較長的社群印象描述文字用來測試字元上限截斷行為_$idx".repeat(2),
            )
        }
        store.insertUnderstanding(
            guildId = "1001",
            scope = MemoryScope.GUILD,
            targetUserId = null,
            subject = "群組梗",
            content = "週五固定開咖啡團",
        )

        val injector = UnderstandingPreInjector(memoryStore = store, maxChars = 250)
        val section =
            injector.buildPreInjectionSection(
                guildId = "1001",
                authorId = "101",
                transcript = "Alice (uid:101): hi",
            )

        assertNotNull(section)
        assertTrue(section.length <= 250, "Expected section length <= 250, got ${section.length}: $section")
        assertFalse(section.contains("週五固定開咖啡團"), "Guild section beyond budget should be truncated")
    }

    @Test
    fun `fails open with warning log when SQLite understanding lookup throws`() {
        val logger = LoggerFactory.getLogger(UnderstandingPreInjector::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val baseStore = createStore()
            val failingStore =
                object : ChatterMemoryStore by baseStore {
                    override fun listUserUnderstandings(
                        guildId: String,
                        userId: String,
                        limit: Int,
                    ): List<SocialUnderstandingRecord> = throw IllegalStateException("sqlite disk io error")
                }

            val injector = UnderstandingPreInjector(memoryStore = failingStore)
            val section =
                injector.buildPreInjectionSection(
                    guildId = "1001",
                    authorId = "101",
                    transcript = "Alice (uid:101): hi",
                )

            assertNull(section)
            val warnMessages = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            assertTrue(
                warnMessages.any { it.contains("UnderstandingPreInjector") || it.contains("Social Understanding") },
                "Expected WARN log on SQLite failure, got: $warnMessages",
            )
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `prioritizes human users mentioned in latest message ahead of older bystander speakers within count and budget limits`() {
        val store = createStore()
        // Author 101
        store.insertUnderstanding("1001", MemoryScope.USER, "101", "author_note", "愛喝拿鐵")
        // Older bystanders 202, 303 in Working Memory Window
        store.insertUnderstanding("1001", MemoryScope.USER, "202", "bystander_bob", "舊群友 Bob 印象")
        store.insertUnderstanding("1001", MemoryScope.USER, "303", "bystander_charlie", "舊群友 Charlie 印象")
        // Mentioned users 404, 505 in latest message (never spoke in transcript)
        repeat(6) { idx ->
            store.insertUnderstanding("1001", MemoryScope.USER, "404", "dave_s$idx", "被提及 Dave 印象 $idx")
        }
        store.insertUnderstanding("1001", MemoryScope.USER, "505", "eve_note", "被提及 Eve 印象")

        val transcript =
            """
            Bob (uid:202): 早安
            Charlie (uid:303): 午安
            Alice (uid:101): 找一下 @Dave (uid:404) 跟 @Eve (uid:505) 討論這件事
            """.trimIndent()

        val injector = UnderstandingPreInjector(memoryStore = store, maxChars = 4000)
        val section = injector.buildPreInjectionSection(guildId = "1001", authorId = "101", transcript = transcript)

        assertNotNull(section)
        assertTrue(section.contains("uid:404"), "Expected mentioned user uid:404 in section")
        assertTrue(section.contains("uid:505"), "Expected mentioned user uid:505 in section")
        assertFalse(section.contains("bystander_bob"), "Expected older bystander Bob to be displaced by 2 mentioned users")
        assertFalse(section.contains("bystander_charlie"), "Expected older bystander Charlie to be displaced by 2 mentioned users")
        assertEquals(5, section.lines().count { it.startsWith("- dave_s") }, "Expected per-speaker entry cap of 5")
    }

    @Test
    fun `fills remaining speaker slots with recent bystanders when latest message mentions only one human user`() {
        val store = createStore()
        store.insertUnderstanding("1001", MemoryScope.USER, "202", "bystander_bob", "較早 Bob")
        store.insertUnderstanding("1001", MemoryScope.USER, "303", "bystander_charlie", "較近 Charlie")
        store.insertUnderstanding("1001", MemoryScope.USER, "404", "mentioned_dave", "被提及 Dave")

        val transcript =
            """
            Bob (uid:202): 較早發言
            Charlie (uid:303): 較近發言
            Alice (uid:101): @Dave (uid:404) 來看看
            """.trimIndent()

        val injector = UnderstandingPreInjector(memoryStore = store, maxChars = 4000)
        val section = injector.buildPreInjectionSection(guildId = "1001", authorId = "101", transcript = transcript)

        assertNotNull(section)
        assertTrue(section.contains("mentioned_dave"), "Expected mentioned user Dave to be included")
        assertTrue(section.contains("bystander_charlie"), "Expected recent bystander Charlie to fill remaining slot")
        assertFalse(section.contains("bystander_bob"), "Expected older bystander Bob to be excluded")
    }

    @Test
    fun `excludes triggering author, Luna, bots, roles, and channels when extracting mentions from latest message`() {
        val store = createStore()
        store.insertUnderstanding("1001", MemoryScope.USER, "101", "author_self", "作者自己")
        store.insertUnderstanding("1001", MemoryScope.USER, "505", "bot_helper", "機器人印象")
        store.insertUnderstanding("1001", MemoryScope.USER, "606", "luna_self", "Luna 自己")
        store.insertUnderstanding("1001", MemoryScope.USER, "404", "human_dave", "人類 Dave")

        val transcript =
            """
            Helper [BOT] (uid:505): 系統通知
            Luna (uid:606): 我在這裡
            Alice (uid:101): @Alice (uid:101) @Luna [YOU] (uid:606) @Helper (uid:505) @Admin (role) #general
            這行接續上一則訊息提到了 @Dave (uid:404) 跟重複的 @Dave (uid:404)
            """.trimIndent()

        val injector = UnderstandingPreInjector(memoryStore = store, maxChars = 4000)
        val section = injector.buildPreInjectionSection(guildId = "1001", authorId = "101", transcript = transcript)

        assertNotNull(section)
        assertTrue(section.contains("human_dave"), "Expected multi-line latest message mention Dave to be included")
        assertFalse(section.contains("bot_helper"), "Expected bot mentioned in latest message to be excluded")
        assertFalse(section.contains("luna_self"), "Expected Luna mentioned in latest message to be excluded")
        assertEquals(1, section.lines().count { it.contains("uid:101") }, "Expected author 101 only once as triggering author")
        assertEquals(1, section.lines().count { it.contains("uid:404") }, "Expected deduplicated Dave 404")
    }
}
