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
}
