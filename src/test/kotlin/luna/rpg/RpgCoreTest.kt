package luna.rpg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RpgCoreTest {
    @Test
    fun testRpgAttributes() {
        val attributes = RpgAttributes(hp = 100, maxHp = 100, atk = 10, def = 5, spd = 8)
        assertEquals(100, attributes.hp)
        assertEquals(100, attributes.maxHp)
        assertEquals(10, attributes.atk)
        assertEquals(5, attributes.def)
        assertEquals(8, attributes.spd)
    }

    @Test
    fun testPlayerModel() {
        val attributes = RpgAttributes(hp = 100, maxHp = 100, atk = 10, def = 5, spd = 8)
        val player = Player(
            id = "player1",
            name = "Hero",
            attributes = attributes,
            recoveryLevel = 1,
            recoveryStartAt = 123456789L
        )
        assertEquals("player1", player.id)
        assertEquals("Hero", player.name)
        assertEquals(attributes, player.attributes)
        assertEquals(1, player.recoveryLevel)
        assertEquals(123456789L, player.recoveryStartAt)
    }

    @Test
    fun testMonsterModel() {
        val attributes = RpgAttributes(hp = 50, maxHp = 50, atk = 5, def = 2, spd = 4)
        val monster = Monster(name = "Slime", attributes = attributes)
        assertEquals("Slime", monster.name)
        assertEquals(attributes, monster.attributes)
    }

    @Test
    fun testCombatSimulation_Resumption() {
        val playerAttr = RpgAttributes(hp = 100, maxHp = 100, atk = 20, def = 10, spd = 10)
        val player = Player(id = "p1", name = "Hero", attributes = playerAttr)
        
        // Monster starting with partial HP (10/50)
        val monsterAttr = RpgAttributes(hp = 10, maxHp = 50, atk = 10, def = 5, spd = 5)
        val monster = Monster(name = "Weak Slime", attributes = monsterAttr)
        
        val result = CombatEngine.simulate(player, monster)
        
        assertTrue(result.won)
        assertEquals(0, result.monsterFinalHP)
        // Player should win quickly
        assertTrue(result.playerFinalHP > 90)
    }
}
