package luna.rpg.command

import kotlin.test.Test
import kotlin.test.assertEquals

class UpgradeListCommandTest {
    @Test
    fun testMetadata() {
        val command = UpgradeListCommand()
        assertEquals("upgrade_list", command.name)
        assertEquals("查看所有裝備升級所需的資源與效果", command.description)
    }
}
