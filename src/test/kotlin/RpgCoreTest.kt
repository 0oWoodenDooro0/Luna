package website.woodendoor.rpg

import kotlin.test.Test
import kotlin.test.assertEquals

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
        val player = Player(id = "player1", name = "Hero", attributes = attributes)
        assertEquals("player1", player.id)
        assertEquals("Hero", player.name)
        assertEquals(attributes, player.attributes)
    }

    @Test
    fun testMonsterModel() {
        val attributes = RpgAttributes(hp = 50, maxHp = 50, atk = 5, def = 2, spd = 4)
        val monster = Monster(name = "Slime", attributes = attributes)
        assertEquals("Slime", monster.name)
        assertEquals(attributes, monster.attributes)
    }
}
