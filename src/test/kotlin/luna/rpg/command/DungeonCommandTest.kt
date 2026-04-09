package luna.rpg.command

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DungeonCommandTest {
    @Test
    fun testDungeonCommandProperties() {
        val command = DungeonCommand()
        assertEquals("dungeon", command.name)
        assertEquals("探索你目前選中的自定義地圖", command.description)
    }
}
