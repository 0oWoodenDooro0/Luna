package luna.core

import dev.kord.common.entity.DiscordChannel
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class KordChannelDeserializationTest {
    @Test
    fun `deserialize channel with null application_id`() {
        val payload =
            """
            {
                "id": "123456789012345678",
                "type": 0,
                "application_id": null
            }
            """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val channel = json.decodeFromString<DiscordChannel>(payload)
        assertNotNull(channel)
    }
}
