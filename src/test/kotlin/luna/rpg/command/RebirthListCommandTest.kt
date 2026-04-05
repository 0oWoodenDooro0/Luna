package luna.rpg.command

import kotlin.test.Test
import kotlin.test.assertEquals

class RebirthListCommandTest {
    @Test
    fun testMetadata() {
        val command = RebirthListCommand()
        assertEquals("rebirth_list", command.name)
        assertEquals("查看所有重生強化項目的成本與效果", command.description)
    }
}
