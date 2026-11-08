package luna.core

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummaryMessageRegistryTest {
    @BeforeEach
    fun setUp() {
        SummaryMessageRegistry.clear()
    }

    @Test
    fun `registers and checks contains for summary message Snowflake`() {
        val messageId = Snowflake(123456789L)
        assertFalse(SummaryMessageRegistry.contains(messageId))

        SummaryMessageRegistry.register(messageId)
        assertTrue(SummaryMessageRegistry.contains(messageId))
        assertEquals(1, SummaryMessageRegistry.size)
    }

    @Test
    fun `registers and checks contains for numeric message ID`() {
        val id = 987654321L
        assertFalse(SummaryMessageRegistry.contains(id))

        SummaryMessageRegistry.register(id)
        assertTrue(SummaryMessageRegistry.contains(id))
        assertTrue(SummaryMessageRegistry.contains(Snowflake(id)))
        assertEquals(1, SummaryMessageRegistry.size)
    }

    @Test
    fun `removes registered summary message ID`() {
        val messageId = Snowflake(55555L)
        SummaryMessageRegistry.register(messageId)
        assertTrue(SummaryMessageRegistry.contains(messageId))

        val removed = SummaryMessageRegistry.remove(messageId)
        assertTrue(removed)
        assertFalse(SummaryMessageRegistry.contains(messageId))
        assertEquals(0, SummaryMessageRegistry.size)
    }

    @Test
    fun `removes registered summary message by numeric ID`() {
        val id = 66666L
        SummaryMessageRegistry.register(id)
        assertTrue(SummaryMessageRegistry.contains(id))

        val removed = SummaryMessageRegistry.remove(id)
        assertTrue(removed)
        assertFalse(SummaryMessageRegistry.contains(id))
        assertEquals(0, SummaryMessageRegistry.size)
    }

    @Test
    fun `clear removes all registered summary messages`() {
        SummaryMessageRegistry.register(1L)
        SummaryMessageRegistry.register(2L)
        SummaryMessageRegistry.register(3L)
        assertEquals(3, SummaryMessageRegistry.size)

        SummaryMessageRegistry.clear()
        assertEquals(0, SummaryMessageRegistry.size)
        assertFalse(SummaryMessageRegistry.contains(1L))
    }

    @Test
    fun `thread-safe concurrent registration and lookup`() =
        runBlocking {
            val jobs =
                (1..100).map { id ->
                    async(Dispatchers.Default) {
                        SummaryMessageRegistry.register(id.toLong())
                        assertTrue(SummaryMessageRegistry.contains(id.toLong()))
                    }
                }
            jobs.awaitAll()
            assertEquals(100, SummaryMessageRegistry.size)
        }

    @Test
    fun `evicts eldest entry when capacity limit is exceeded`() {
        val customRegistry = BoundedSummaryRegistry(maxEntries = 3)
        customRegistry.register(101L)
        customRegistry.register(102L)
        customRegistry.register(103L)
        assertEquals(3, customRegistry.size)
        assertTrue(customRegistry.contains(101L))

        // Adding 4th element should evict the eldest (101L)
        customRegistry.register(104L)
        assertEquals(3, customRegistry.size)
        assertFalse(customRegistry.contains(101L))
        assertTrue(customRegistry.contains(102L))
        assertTrue(customRegistry.contains(103L))
        assertTrue(customRegistry.contains(104L))
    }

    @Test
    fun `structural summary content detection identifies keywords and prefixes`() {
        assertTrue(SummaryRegistry.isSummaryContent("TL;DR: This is a summary"))
        assertTrue(SummaryRegistry.isSummaryContent("TL;DR"))
        assertTrue(SummaryRegistry.isSummaryContent("  TL;DR: with leading whitespace"))
        assertTrue(SummaryRegistry.isSummaryContent("這段對話的重點摘要如下："))
        assertTrue(SummaryRegistry.isSummaryContent("Previous summary from discussion"))
        assertFalse(SummaryRegistry.isSummaryContent("Hello everyone, how are you?"))
        assertFalse(SummaryRegistry.isSummaryContent("I don't think that's right"))
    }
}
