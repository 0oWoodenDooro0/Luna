package luna.rpg.command

import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class HelpCommandTest {
    @Test
    fun testMetadata() {
        val command = HelpCommand()
        assertEquals("help", command.name)
        assertEquals("查看 RPG 指令列表與說明", command.description)
    }
}
