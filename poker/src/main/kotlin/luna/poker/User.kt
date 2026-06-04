package luna.poker

import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a poker player/user.
 * Each user maintains their own personal deck of cards, which is automatically shuffled upon initialization.
 */
class User(val id: String) {
    @Volatile
    var deck: Deck = Deck.standard52().apply { shuffle() }
        private set

    @Volatile
    var drawCount: Int = 1

    @Volatile
    var score: Int = 0

    /**
     * Gets the score cost to upgrade the draw count to the next level.
     * Returns null if already at max draw count (7).
     */
    fun getNextDrawUpgradeCost(): Int? {
        return DRAW_UPGRADE_COSTS[drawCount]
    }

    /**
     * Attempts to upgrade the draw count.
     * Deducts the score and increments drawCount if successful.
     */
    fun upgradeDrawCount(): Boolean {
        val cost = getNextDrawUpgradeCost() ?: return false
        if (score >= cost && drawCount < 7) {
            score -= cost
            drawCount += 1
            return true
        }
        return false
    }
    /**
     * Resets the user's personal deck back to a standard 52-card deck and shuffles it.
     */
    fun resetDeck() {
        deck = Deck.standard52().apply { shuffle() }
    }

    companion object {
        private val users = ConcurrentHashMap<String, User>()

        val DRAW_UPGRADE_COSTS = mapOf(
            1 to 100,
            2 to 5000,
            3 to 15000,
            4 to 50000,
            5 to 150000,
            6 to 500000
        )

        /**
         * Retrieves an existing user or creates a new one with their personal deck.
         */
        fun getOrCreate(id: String): User {
            return users.computeIfAbsent(id) { User(it) }
        }

        /**
         * Clears all cached users. Primarily used for testing purposes.
         */
        fun clearAll() {
            users.clear()
        }
    }
}
