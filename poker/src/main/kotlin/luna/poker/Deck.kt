package luna.poker

import java.util.Collections

class Deck(initialCards: Collection<Card>) {
    private val cardList = ArrayList<Card>(initialCards)
    private val lock = Any()

    val remainingCount: Int
        get() = synchronized(lock) { cardList.size }

    fun getCards(): List<Card> = synchronized(lock) {
        ArrayList(cardList)
    }

    fun shuffle() {
        synchronized(lock) {
            cardList.shuffle()
        }
    }

    /**
     * Draws the specified number of cards from the top of the deck.
     * This operation is thread-safe and removes cards from the deck (without replacement).
     *
     * @param count The number of cards to draw.
     * @return A list of drawn cards.
     * @throws IllegalArgumentException if the deck does not contain enough cards.
     */
    fun draw(count: Int): List<Card> {
        require(count >= 0) { "Draw count must be non-negative: $count" }
        return synchronized(lock) {
            if (count > cardList.size) {
                throw IllegalArgumentException("Cannot draw $count cards, only $remainingCount remaining in the deck")
            }
            val drawn = ArrayList<Card>(count)
            repeat(count) {
                drawn.add(cardList.removeAt(0))
            }
            drawn
        }
    }

    companion object {
        /**
         * Creates a standard 52-card deck containing one of each suit and rank combination.
         */
        fun standard52(): Deck {
            val cards = ArrayList<Card>()
            for (suit in Suit.entries) {
                for (rank in Rank.entries) {
                    cards.add(Card(suit, rank))
                }
            }
            return Deck(cards)
        }

        /**
         * Creates a multi-deck shoe consisting of [deckCount] standard 52-card decks.
         */
        fun multiDeck(deckCount: Int): Deck {
            require(deckCount > 0) { "Deck count must be positive: $deckCount" }
            val cards = ArrayList<Card>()
            repeat(deckCount) {
                for (suit in Suit.entries) {
                    for (rank in Rank.entries) {
                        cards.add(Card(suit, rank))
                    }
                }
            }
            return Deck(cards)
        }
    }
}
