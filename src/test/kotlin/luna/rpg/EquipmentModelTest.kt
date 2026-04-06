package luna.rpg

import kotlin.test.Test
import kotlin.test.assertEquals

class EquipmentModelTest {
    @Test
    fun testPlayerEffectiveAttributes() {
        val baseStats = RpgAttributes(hp = 100, maxHp = 100, atk = 20, def = 20, spd = 8)
        val player =
            Player(
                id = "player1",
                name = "Hero",
                attributes = baseStats,
                weaponLevel = 2,
                shieldLevel = 3,
                armorLevel = 1,
            )

        val effective = player.effectiveAttributes
        assertEquals(100, effective.hp)
        assertEquals(100, effective.maxHp)
        assertEquals(20, effective.atk)
        assertEquals(20, effective.def)
        assertEquals(8, effective.spd)
    }
}
