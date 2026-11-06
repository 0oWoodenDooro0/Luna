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

class LunaWebhookRegistryTest {
    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
    }

    @Test
    fun `registers and checks contains for webhook Snowflake`() {
        val webhookId = Snowflake(123456789L)
        assertFalse(LunaWebhookRegistry.contains(webhookId))

        LunaWebhookRegistry.register(webhookId)
        assertTrue(LunaWebhookRegistry.contains(webhookId))
        assertEquals(1, LunaWebhookRegistry.size)
    }

    @Test
    fun `registers and checks contains for numeric webhook ID`() {
        val id = 987654321L
        assertFalse(LunaWebhookRegistry.contains(id))

        LunaWebhookRegistry.register(id)
        assertTrue(LunaWebhookRegistry.contains(id))
        assertTrue(LunaWebhookRegistry.contains(Snowflake(id)))
        assertEquals(1, LunaWebhookRegistry.size)
    }

    @Test
    fun `removes registered webhook ID`() {
        val webhookId = Snowflake(55555L)
        LunaWebhookRegistry.register(webhookId)
        assertTrue(LunaWebhookRegistry.contains(webhookId))

        val removed = LunaWebhookRegistry.remove(webhookId)
        assertTrue(removed)
        assertFalse(LunaWebhookRegistry.contains(webhookId))
        assertEquals(0, LunaWebhookRegistry.size)
    }

    @Test
    fun `clear removes all registered webhooks`() {
        LunaWebhookRegistry.register(1L)
        LunaWebhookRegistry.register(2L)
        LunaWebhookRegistry.register(3L)
        assertEquals(3, LunaWebhookRegistry.size)

        LunaWebhookRegistry.clear()
        assertEquals(0, LunaWebhookRegistry.size)
        assertFalse(LunaWebhookRegistry.contains(1L))
    }

    @Test
    fun `thread-safe concurrent registration and lookup`() =
        runBlocking {
            val jobs =
                (1..100).map { id ->
                    async(Dispatchers.Default) {
                        LunaWebhookRegistry.register(id.toLong())
                        assertTrue(LunaWebhookRegistry.contains(id.toLong()))
                    }
                }
            jobs.awaitAll()
            assertEquals(100, LunaWebhookRegistry.size)
        }
}
