package luna.rpg.command

import kotlin.test.Test
import kotlin.test.assertEquals

class StatusCommandTest {
    @Test
    fun testMetadata() {
        val command = StatusCommand()
        assertEquals("status", command.name)
        assertEquals("查看你的角色屬性與資源", command.description)
    }
}
