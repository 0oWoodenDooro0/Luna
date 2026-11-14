package luna.chatter

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedChannelInFlightTrackerTest {
    @Test
    fun `tryAcquire, acquire, and release track reference counts per channel and clear only at zero`() {
        val tracker = BoundedChannelInFlightTracker()
        val channelId = Snowflake(100L)

        assertFalse(tracker.isInFlight(channelId))
        assertEquals(0, tracker.activeCount(channelId))
        assertEquals(0, tracker.size)

        // First ambient acquisition succeeds
        assertTrue(tracker.tryAcquire(channelId))
        assertTrue(tracker.isInFlight(channelId))
        assertEquals(1, tracker.activeCount(channelId))
        assertEquals(1, tracker.size)

        // Concurrent ambient acquisition on same channel fails while active
        assertFalse(tracker.tryAcquire(channelId))
        assertEquals(1, tracker.activeCount(channelId))

        // Direct mention increments reference count cleanly
        assertEquals(2, tracker.acquire(channelId))
        assertEquals(3, tracker.acquire(channelId))
        assertTrue(tracker.isInFlight(channelId))
        assertEquals(3, tracker.activeCount(channelId))

        // Releasing down to 1 keeps channel active and blocks ambient tryAcquire
        assertEquals(2, tracker.release(channelId))
        assertTrue(tracker.isInFlight(channelId))
        assertFalse(tracker.tryAcquire(channelId))

        assertEquals(1, tracker.release(channelId))
        assertTrue(tracker.isInFlight(channelId))
        assertFalse(tracker.tryAcquire(channelId))

        // Final release clears channel from active map
        assertEquals(0, tracker.release(channelId))
        assertFalse(tracker.isInFlight(channelId))
        assertEquals(0, tracker.activeCount(channelId))
        assertEquals(0, tracker.size)

        // Extra release on idle channel stays zero (never negative)
        assertEquals(0, tracker.release(channelId))
        assertFalse(tracker.isInFlight(channelId))
        assertEquals(0, tracker.size)

        // Channel is now available for ambient tryAcquire again
        assertTrue(tracker.tryAcquire(channelId))
        assertEquals(1, tracker.activeCount(channelId))
    }

    @Test
    fun `evicts least recently used channel when active channels exceed max capacity of 500`() {
        assertEquals(500, BoundedChannelInFlightTracker.DEFAULT_MAX_CAPACITY)
        val tracker = BoundedChannelInFlightTracker()

        for (i in 1L..500L) {
            assertTrue(tracker.tryAcquire(i))
        }
        assertEquals(500, tracker.size)

        // Touch channel 1L via acquire so channel 2L becomes least recently used
        assertEquals(2, tracker.acquire(1L))

        // Adding channel 501L must evict LRU channel 2L while retaining 1L and 501L
        assertTrue(tracker.tryAcquire(501L))
        assertEquals(500, tracker.size)
        assertFalse(tracker.isInFlight(2L))
        assertTrue(tracker.isInFlight(1L))
        assertEquals(2, tracker.activeCount(1L))
        assertTrue(tracker.isInFlight(501L))
    }

    @Test
    fun `rejects non-positive maxEntries boundary`() {
        assertFailsWith<IllegalArgumentException> {
            BoundedChannelInFlightTracker(maxEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BoundedChannelInFlightTracker(maxEntries = -5)
        }
    }

    @Test
    fun `concurrent acquire and release preserve exact reference count`() =
        runTest {
            val tracker = BoundedChannelInFlightTracker()
            val channelId = Snowflake(777L)

            val jobs =
                (1..64).map {
                    async(Dispatchers.Default) {
                        tracker.acquire(channelId)
                        Thread.yield()
                        tracker.release(channelId)
                    }
                }
            jobs.awaitAll()

            assertFalse(tracker.isInFlight(channelId))
            assertEquals(0, tracker.activeCount(channelId))
            assertEquals(0, tracker.size)

            // Only one concurrent tryAcquire wins when channel starts idle
            val winners =
                (1..32)
                    .map {
                        async(Dispatchers.Default) {
                            tracker.tryAcquire(channelId)
                        }
                    }.awaitAll()
            assertEquals(1, winners.count { it })
            assertEquals(1, tracker.activeCount(channelId))
        }
}
