package luna.chatter.emoji

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import luna.chatter.GuildCustomEmoji
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationEmojiCacheTest {
    private class MutableClock(
        initial: Instant,
    ) : Clock() {
        private val current = AtomicReference(initial)

        fun advance(duration: Duration) {
            current.updateAndGet { it.plus(duration) }
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current.get()
    }

    @Test
    fun `getOrRefresh serves cached snapshot within 10-minute TTL and refreshes on expiry`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-21T10:00:00Z"))
            val cache = ApplicationEmojiCache(clock = clock)
            val fetchCount = AtomicInteger(0)
            val firstBatch = listOf(GuildCustomEmoji(Snowflake(101L), "luna_smug", false))
            val secondBatch =
                listOf(
                    GuildCustomEmoji(Snowflake(101L), "luna_smug", false),
                    GuildCustomEmoji(Snowflake(102L), "luna_pout", true),
                )

            assertTrue(cache.knownIds().isEmpty(), "Expected knownIds to be empty before initial load")

            val initial =
                cache.getOrRefresh(
                    fetcher = {
                        fetchCount.incrementAndGet()
                        firstBatch
                    },
                )
            assertEquals(firstBatch, initial)
            assertEquals(setOf("101"), cache.knownIds())
            assertEquals(1, fetchCount.get())

            clock.advance(Duration.ofMillis(ApplicationEmojiCache.DEFAULT_TTL_MILLIS - 1L))
            val hit =
                cache.getOrRefresh(
                    fetcher = {
                        fetchCount.incrementAndGet()
                        secondBatch
                    },
                )
            assertEquals(firstBatch, hit)
            assertEquals(1, fetchCount.get(), "Expected cache hit before TTL expiry without invoking fetcher")

            clock.advance(Duration.ofMillis(1L))
            val refreshed =
                cache.getOrRefresh(
                    fetcher = {
                        fetchCount.incrementAndGet()
                        secondBatch
                    },
                )
            assertEquals(secondBatch, refreshed)
            assertEquals(setOf("101", "102"), cache.knownIds())
            assertEquals(2, fetchCount.get(), "Expected fetcher invocation after TTL expiry")
        }

    @Test
    fun `getOrRefresh invokes onNewEmojisDiscovered only for newly discovered IDs`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-21T10:00:00Z"))
            val cache = ApplicationEmojiCache(clock = clock)
            val discoveredBatches = mutableListOf<List<GuildCustomEmoji>>()

            val emojiA = GuildCustomEmoji(Snowflake(201L), "luna_wink", false)
            val emojiB = GuildCustomEmoji(Snowflake(202L), "luna_blush", true)

            cache.getOrRefresh(
                fetcher = { listOf(emojiA) },
                onNewEmojisDiscovered = { discoveredBatches += it },
            )
            assertEquals(listOf(listOf(emojiA)), discoveredBatches)

            clock.advance(Duration.ofMinutes(10))
            cache.getOrRefresh(
                fetcher = { listOf(emojiA) },
                onNewEmojisDiscovered = { discoveredBatches += it },
            )
            assertEquals(1, discoveredBatches.size, "Expected no callback when no new emoji IDs were discovered")

            clock.advance(Duration.ofMinutes(10))
            cache.getOrRefresh(
                fetcher = { listOf(emojiA, emojiB) },
                onNewEmojisDiscovered = { discoveredBatches += it },
            )
            assertEquals(listOf(listOf(emojiA), listOf(emojiB)), discoveredBatches)
        }

    @Test
    fun `transient failures are not cached and expired cache serves stale snapshot with warning logs`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-21T10:00:00Z"))
            val cache = ApplicationEmojiCache(clock = clock)

            val cacheLogger = LoggerFactory.getLogger(ApplicationEmojiCache::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            cacheLogger.addAppender(appender)

            try {
                val coldFallback =
                    cache.getOrRefresh(
                        fetcher = { throw IllegalStateException("Discord 503 cold") },
                    )
                assertTrue(coldFallback.isEmpty(), "Expected empty list on cold-cache transient failure")
                assertTrue(cache.knownIds().isEmpty(), "Expected empty knownIds after cold-cache transient failure")

                val emojiA = GuildCustomEmoji(Snowflake(301L), "luna_ok", false)
                val loaded = cache.getOrRefresh(fetcher = { listOf(emojiA) })
                assertEquals(listOf(emojiA), loaded)

                clock.advance(Duration.ofMinutes(10))
                val staleFallback =
                    cache.getOrRefresh(
                        fetcher = { throw IllegalStateException("Discord 429 refresh") },
                    )
                assertEquals(listOf(emojiA), staleFallback, "Expected stale snapshot on transient refresh failure")

                val emojiB = GuildCustomEmoji(Snowflake(302L), "luna_recovered", true)
                val recovered = cache.getOrRefresh(fetcher = { listOf(emojiA, emojiB) })
                assertEquals(listOf(emojiA, emojiB), recovered, "Expected immediate retry after transient failure")

                val warnLogs = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
                assertTrue(
                    warnLogs.any { it.contains("Discord 503 cold") },
                    "Expected warning log for cold transient failure, got: $warnLogs",
                )
                assertTrue(
                    warnLogs.any { it.contains("Discord 429 refresh") && it.contains("stale snapshot") },
                    "Expected warning log for stale fallback on refresh failure, got: $warnLogs",
                )
            } finally {
                cacheLogger.detachAppender(appender)
            }
        }

    @Test
    fun `CancellationException propagates unchanged and legitimate empty results are cached within TTL`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-21T10:00:00Z"))
            val cache = ApplicationEmojiCache(clock = clock)

            assertThrows<CancellationException> {
                cache.getOrRefresh(fetcher = { throw CancellationException("cancelled fetch") })
            }
            assertTrue(cache.knownIds().isEmpty(), "Expected cache to remain unpopulated after CancellationException")

            val fetchCalls = AtomicInteger(0)
            val firstEmpty =
                cache.getOrRefresh(
                    fetcher = {
                        fetchCalls.incrementAndGet()
                        emptyList()
                    },
                )
            assertTrue(firstEmpty.isEmpty(), "Expected empty list from successful empty fetch")

            clock.advance(Duration.ofMinutes(5))
            val secondEmpty =
                cache.getOrRefresh(
                    fetcher = {
                        fetchCalls.incrementAndGet()
                        listOf(GuildCustomEmoji(Snowflake(401L), "should_not_fetch_yet", false))
                    },
                )
            assertTrue(secondEmpty.isEmpty(), "Expected cached empty list within TTL")
            assertEquals(1, fetchCalls.get(), "Expected successful empty fetch to be cached within TTL")
        }
}
