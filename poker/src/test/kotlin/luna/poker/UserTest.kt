package luna.poker

import kotlin.test.*

class UserTest {

    @BeforeTest
    fun setUp() {
        User.clearAll()
    }

    @Test
    fun testGetOrCreateUser() {
        val user1 = User.getOrCreate("user-1")
        val user2 = User.getOrCreate("user-2")
        val user1Duplicate = User.getOrCreate("user-1")

        assertNotNull(user1)
        assertNotNull(user2)
        assertSame(user1, user1Duplicate)
        assertNotSame(user1, user2)
    }

    @Test
    fun testDeckInitialization() {
        val user = User.getOrCreate("user-abc")
        assertEquals(52, user.deck.remainingCount)
        assertEquals(1, user.drawCount)

        // Draw 5 cards and verify count drops
        val drawn = user.deck.draw(5)
        assertEquals(5, drawn.size)
        assertEquals(47, user.deck.remainingCount)
    }

    @Test
    fun testDeckReset() {
        val user = User.getOrCreate("user-xyz")
        user.deck.draw(10)
        assertEquals(42, user.deck.remainingCount)

        user.resetDeck()
        assertEquals(52, user.deck.remainingCount)
    }

    @Test
    fun testUserScore() {
        val user = User.getOrCreate("user-score-test")
        assertEquals(0, user.score)
        
        user.score += 100
        assertEquals(100, user.score)
        
        user.score += 50
        assertEquals(150, user.score)
    }

    @Test
    fun testDrawUpgrade() {
        val user = User.getOrCreate("user-upgrade-test")
        assertEquals(1, user.drawCount)
        assertEquals(100, user.getNextDrawUpgradeCost())

        // Try to upgrade without enough score
        assertFalse(user.upgradeDrawCount())
        assertEquals(1, user.drawCount)

        // Add score and upgrade to level 2
        user.score = 100
        assertTrue(user.upgradeDrawCount())
        assertEquals(2, user.drawCount)
        assertEquals(0, user.score)
        assertEquals(5000, user.getNextDrawUpgradeCost())

        // Upgrade through all levels up to max (7)
        user.score = 5000
        assertTrue(user.upgradeDrawCount()) // 2 -> 3
        assertEquals(3, user.drawCount)

        user.score = 15000
        assertTrue(user.upgradeDrawCount()) // 3 -> 4
        
        user.score = 50000
        assertTrue(user.upgradeDrawCount()) // 4 -> 5
        
        user.score = 150000
        assertTrue(user.upgradeDrawCount()) // 5 -> 6

        user.score = 500000
        assertTrue(user.upgradeDrawCount()) // 6 -> 7
        assertEquals(7, user.drawCount)
        assertNull(user.getNextDrawUpgradeCost())

        // Try to upgrade past max (7)
        user.score = 1000000
        assertFalse(user.upgradeDrawCount())
        assertEquals(7, user.drawCount)
    }
}
