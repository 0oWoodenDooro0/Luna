package website.woodendoor.rpg

import kotlin.test.Test
import kotlin.test.assertEquals

class EquipmentModelTest {

    @Test
    fun testPlayerEffectiveAttributes() {
        val baseStats = RpgAttributes(hp = 100, maxHp = 100, atk = 10, def = 5, spd = 8)
        val player = Player(
            id = "player1",
            name = "Hero",
            attributes = baseStats,
            weaponLevel = 2, // ATK +10
            shieldLevel = 3, // DEF +15
            armorLevel = 1   // MaxHP +5
        )
        
        // Assuming +5 per level
        val effective = player.effectiveAttributes
        assertEquals(105, effective.hp)
        assertEquals(105, effective.maxHp)
        assertEquals(20, effective.atk)
        assertEquals(20, effective.def)
        assertEquals(8, effective.spd)
    }
}
