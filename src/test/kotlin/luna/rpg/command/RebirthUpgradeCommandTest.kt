package luna.rpg.command

import kotlin.test.Test
import kotlin.test.assertEquals

class RebirthUpgradeCommandTest {
    @Test
    fun testMetadata() {
        val command = RebirthUpgradeCommand()
        assertEquals("rebirth_upgrade", command.name)
        assertEquals("使用重生點數強化你的永久屬性", command.description)
    }
}
