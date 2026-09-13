package luna.preview

import dev.socialpeek.model.Platform
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewSettingsStorageTest {
    private fun createTestDb(): Database =
        Database.connect("jdbc:h2:mem:test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")

    @Test
    fun `test cleanUrlEnabled default and toggling`() {
        val db = createTestDb()
        val storage = PreviewSettingsStorage(db)
        val guildId = "guild_123"

        // Default should be true
        assertTrue(storage.isCleanUrlEnabled(guildId))

        // Set to false
        storage.setCleanUrlEnabled(guildId, false)
        assertFalse(storage.isCleanUrlEnabled(guildId))

        // Set back to true
        storage.setCleanUrlEnabled(guildId, true)
        assertTrue(storage.isCleanUrlEnabled(guildId))
    }

    @Test
    fun `test webhookReplaceEnabled default and toggling`() {
        val db = createTestDb()
        val storage = PreviewSettingsStorage(db)
        val guildId = "guild_webhook_123"

        // Default should be true
        assertTrue(storage.isWebhookReplaceEnabled(guildId))

        // Set to false
        storage.setWebhookReplaceEnabled(guildId, false)
        assertFalse(storage.isWebhookReplaceEnabled(guildId))

        // Set back to true
        storage.setWebhookReplaceEnabled(guildId, true)
        assertTrue(storage.isWebhookReplaceEnabled(guildId))
    }

    @Test
    fun `test platform toggle and clean url persistence together`() {
        val db = createTestDb()
        val storage = PreviewSettingsStorage(db)
        val guildId = "guild_456"

        storage.setPlatformEnabled(guildId, Platform.X, false)
        assertFalse(storage.isPlatformEnabled(guildId, Platform.X))
        assertTrue(storage.isPlatformEnabled(guildId, Platform.BILIBILI))
        assertTrue(storage.isCleanUrlEnabled(guildId))
        assertTrue(storage.isWebhookReplaceEnabled(guildId))

        storage.setCleanUrlEnabled(guildId, false)
        assertFalse(storage.isCleanUrlEnabled(guildId))
        assertFalse(storage.isPlatformEnabled(guildId, Platform.X))
        assertTrue(storage.isWebhookReplaceEnabled(guildId))

        storage.setWebhookReplaceEnabled(guildId, false)
        assertFalse(storage.isWebhookReplaceEnabled(guildId))
        assertFalse(storage.isCleanUrlEnabled(guildId))
        assertFalse(storage.isPlatformEnabled(guildId, Platform.X))

        storage.setPlatformEnabled(guildId, Platform.X, true)
        assertTrue(storage.isPlatformEnabled(guildId, Platform.X))
        assertFalse(storage.isCleanUrlEnabled(guildId))
        assertFalse(storage.isWebhookReplaceEnabled(guildId))
    }

    @Test
    fun `test guild enabled total toggle`() {
        val db = createTestDb()
        val storage = PreviewSettingsStorage(db)
        val guildId = "guild_789"

        assertTrue(storage.isGuildEnabled(guildId))
        storage.setGuildEnabled(guildId, false)
        assertFalse(storage.isGuildEnabled(guildId))
        assertEquals(false, storage.isPlatformEnabled(guildId, Platform.YOUTUBE))

        storage.setGuildEnabled(guildId, true)
        assertTrue(storage.isGuildEnabled(guildId))
        assertTrue(storage.isPlatformEnabled(guildId, Platform.YOUTUBE))
    }
}
