package luna.poker

import kotlin.test.*

class DeckTest {

    @Test
    fun testCardRepresentation() {
        val threeOfClubs = Card(Suit.CLUBS, Rank.THREE)
        assertEquals("3♣", threeOfClubs.toString())
        assertEquals("梅花3", threeOfClubs.displayName)

        val aceOfSpades = Card(Suit.SPADES, Rank.ACE)
        assertEquals("A♠", aceOfSpades.toString())
        assertEquals("黑桃A", aceOfSpades.displayName)
    }

    @Test
    fun testStandardDeckInitialization() {
        val deck = Deck.standard52()
        assertEquals(52, deck.remainingCount)
        assertEquals(52, deck.getCards().size)

        // Ensure all combinations exist and are unique in standard 52 deck
        val uniqueCards = deck.getCards().toSet()
        assertEquals(52, uniqueCards.size)
    }

    @Test
    fun testMultiDeckInitialization() {
        val doubleDeck = Deck.multiDeck(2)
        assertEquals(104, doubleDeck.remainingCount)

        // There should be exactly two of each unique card
        val occurrences = doubleDeck.getCards().groupingBy { it }.eachCount()
        assertEquals(52, occurrences.size)
        occurrences.forEach { (_, count) ->
            assertEquals(2, count)
        }
    }

    @Test
    fun testDrawNoReplacement() {
        val c3 = Card(Suit.CLUBS, Rank.THREE)
        val h4 = Card(Suit.HEARTS, Rank.FOUR)
        
        // Initializing with one 3 of Clubs and one 4 of Hearts
        val deck = Deck(listOf(c3, h4))
        assertEquals(2, deck.remainingCount)

        val drawn = deck.draw(2)
        assertEquals(2, drawn.size)
        assertTrue(drawn.contains(c3))
        assertTrue(drawn.contains(h4))
        assertEquals(0, deck.remainingCount)
    }

    @Test
    fun testDrawDuplicatesRule() {
        val c3 = Card(Suit.CLUBS, Rank.THREE)
        val h4 = Card(Suit.HEARTS, Rank.FOUR)
        
        // Deck only contains ONE 3 of Clubs.
        // We draw 2 cards. It cannot contain two 3 of Clubs because it was removed from the deck upon drawing.
        val deck = Deck(listOf(c3, h4))
        
        val drawn = deck.draw(2)
        
        // Count how many Three of Clubs were drawn
        val c3Count = drawn.count { it == c3 }
        assertEquals(1, c3Count)
    }

    @Test
    fun testDrawWithActualDuplicatesInDeck() {
        val c3 = Card(Suit.CLUBS, Rank.THREE)
        val h4 = Card(Suit.HEARTS, Rank.FOUR)
        
        // Deck contains TWO 3 of Clubs, and ONE 4 of Hearts.
        val deck = Deck(listOf(c3, c3, h4))
        assertEquals(3, deck.remainingCount)

        // Draw 2 cards.
        val drawn = deck.draw(2)
        assertEquals(2, drawn.size)
        
        // Draw the remaining 1 card.
        val remainingDrawn = deck.draw(1)
        assertEquals(1, remainingDrawn.size)
        assertEquals(0, deck.remainingCount)

        val allDrawn = drawn + remainingDrawn
        assertEquals(2, allDrawn.count { it == c3 })
        assertEquals(1, allDrawn.count { it == h4 })
    }

    @Test
    fun testDrawInsufficientCardsThrows() {
        val deck = Deck(listOf(Card(Suit.CLUBS, Rank.THREE)))
        val exception = assertFailsWith<IllegalArgumentException> {
            deck.draw(2)
        }
        assertTrue(exception.message!!.contains("Cannot draw 2 cards, only 1 remaining"))
    }

    @Test
    fun testShuffle() {
        val deck = Deck.standard52()
        val originalCards = deck.getCards()
        
        deck.shuffle()
        val shuffledCards = deck.getCards()
        
        assertEquals(originalCards.size, shuffledCards.size)
        assertEquals(originalCards.toSet(), shuffledCards.toSet())
        
        // It is highly unlikely for a shuffled 52-card deck to match its original order perfectly
        assertNotEquals(originalCards, shuffledCards)
    }
}
