package website.woodendoor.rpg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RpgConfigTest {
    @Test
    fun testRecoveryConfig() {
        // These should exist after implementation
        assertEquals(0.1, RpgConfig.RECOVERY_BASE_SECONDS_PER_HP)
        assertEquals(5.0, RpgConfig.RECOVERY_UPGRADE_REDUCTION_SECONDS)
        assertEquals(5.0, RpgConfig.RECOVERY_MIN_SECONDS)
        assertTrue(RpgConfig.UPGRADE_REQUIREMENTS.containsKey("recovery"))
    }
}
