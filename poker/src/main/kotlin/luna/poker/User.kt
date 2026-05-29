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

    /**
     * Resets the user's personal deck back to a standard 52-card deck and shuffles it.
     */
    fun resetDeck() {
        deck = Deck.standard52().apply { shuffle() }
    }

    companion object {
        private val users = ConcurrentHashMap<String, User>()

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
