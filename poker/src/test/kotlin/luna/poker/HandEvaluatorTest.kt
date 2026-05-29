package luna.poker

import kotlin.test.*

class HandEvaluatorTest {

    private val evaluator = HandEvaluator()

    @Test
    fun testHighCardScoring() {
        val cards = listOf(
            Card(Suit.SPADES, Rank.TWO),     // rank: 2, suit: 4
            Card(Suit.DIAMONDS, Rank.FOUR),   // rank: 4, suit: 2
            Card(Suit.HEARTS, Rank.FIVE),    // rank: 5, suit: 3
            Card(Suit.CLUBS, Rank.SEVEN),    // rank: 7, suit: 1
            Card(Suit.SPADES, Rank.NINE)     // rank: 9, suit: 4
        )
        // Rank sum: 2 + 4 + 5 + 7 + 9 = 27
        // Suit sum: 4 + 2 + 3 + 1 + 4 = 14
        // High Card multiplier = 1
        // Score = 27 * 14 * 1 = 378
        assertEquals(HandType.HIGH_CARD, evaluator.evaluate(cards))
        assertEquals(378, evaluator.calculateScore(cards))
    }

    @Test
    fun testOnePairScoring() {
        val cards = listOf(
            Card(Suit.CLUBS, Rank.THREE),   // Pair card 1 (rank: 3, suit: 1)
            Card(Suit.SPADES, Rank.THREE),  // Pair card 2 (rank: 3, suit: 4)
            Card(Suit.DIAMONDS, Rank.FIVE),  // Kicker (ignored in score)
            Card(Suit.HEARTS, Rank.EIGHT),   // Kicker (ignored in score)
            Card(Suit.SPADES, Rank.JACK)    // Kicker (ignored in score)
        )
        // Target: Pair of Threes
        // Rank sum: 3 + 3 = 6
        // Suit sum: 1 + 4 = 5
        // One Pair multiplier = 2
        // Score = 6 * 5 * 2 = 60
        assertEquals(HandType.ONE_PAIR, evaluator.evaluate(cards))
        assertEquals(60, evaluator.calculateScore(cards))
    }

    @Test
    fun testTwoPairScoring() {
        val cards = listOf(
            Card(Suit.DIAMONDS, Rank.FOUR),  // Pair 1a (rank: 4, suit: 2)
            Card(Suit.CLUBS, Rank.FOUR),     // Pair 1b (rank: 4, suit: 1)
            Card(Suit.HEARTS, Rank.EIGHT),   // Pair 2a (rank: 8, suit: 3)
            Card(Suit.SPADES, Rank.EIGHT),   // Pair 2b (rank: 8, suit: 4)
            Card(Suit.SPADES, Rank.KING)     // Kicker (ignored in score)
        )
        // Target: Two Pairs
        // Rank sum: 4 + 4 + 8 + 8 = 24
        // Suit sum: 2 + 1 + 3 + 4 = 10
        // Two Pair multiplier = 3
        // Score = 24 * 10 * 3 = 720
        assertEquals(HandType.TWO_PAIR, evaluator.evaluate(cards))
        assertEquals(720, evaluator.calculateScore(cards))
    }

    @Test
    fun testThreeOfAKindScoring() {
        val cards = listOf(
            Card(Suit.CLUBS, Rank.QUEEN),    // Three 1 (rank: 12, suit: 1)
            Card(Suit.SPADES, Rank.QUEEN),   // Three 2 (rank: 12, suit: 4)
            Card(Suit.DIAMONDS, Rank.QUEEN), // Three 3 (rank: 12, suit: 2)
            Card(Suit.HEARTS, Rank.FIVE),    // Kicker
            Card(Suit.SPADES, Rank.TWO)      // Kicker
        )
        // Target: Three Queens
        // Rank sum: 12 + 12 + 12 = 36
        // Suit sum: 1 + 4 + 2 = 7
        // Three of a Kind multiplier = 4
        // Score = 36 * 7 * 4 = 1008
        assertEquals(HandType.THREE_OF_A_KIND, evaluator.evaluate(cards))
        assertEquals(1008, evaluator.calculateScore(cards))
    }

    @Test
    fun testStraightScoring() {
        val cards = listOf(
            Card(Suit.CLUBS, Rank.FIVE),
            Card(Suit.DIAMONDS, Rank.SIX),
            Card(Suit.HEARTS, Rank.SEVEN),
            Card(Suit.SPADES, Rank.EIGHT),
            Card(Suit.SPADES, Rank.NINE)
        )
        // Rank sum: 5 + 6 + 7 + 8 + 9 = 35
        // Suit sum: 1 + 2 + 3 + 4 + 4 = 14
        // Straight multiplier = 5
        // Score = 35 * 14 * 5 = 2450
        assertEquals(HandType.STRAIGHT, evaluator.evaluate(cards))
        assertEquals(2450, evaluator.calculateScore(cards))
    }

    @Test
    fun testFlushScoring() {
        val cards = listOf(
            Card(Suit.HEARTS, Rank.TWO),
            Card(Suit.HEARTS, Rank.FIVE),
            Card(Suit.HEARTS, Rank.SEVEN),
            Card(Suit.HEARTS, Rank.JACK),
            Card(Suit.HEARTS, Rank.KING)
        )
        // Rank sum: 2 + 5 + 7 + 11 + 13 = 38
        // Suit sum: 3 + 3 + 3 + 3 + 3 = 15
        // Flush multiplier = 6
        // Score = 38 * 15 * 6 = 3420
        assertEquals(HandType.FLUSH, evaluator.evaluate(cards))
        assertEquals(3420, evaluator.calculateScore(cards))
    }

    @Test
    fun testFullHouseScoring() {
        val cards = listOf(
            Card(Suit.CLUBS, Rank.SEVEN),
            Card(Suit.DIAMONDS, Rank.SEVEN),
            Card(Suit.SPADES, Rank.SEVEN),
            Card(Suit.HEARTS, Rank.KING),
            Card(Suit.SPADES, Rank.KING)
        )
        // Rank sum: 7 + 7 + 7 + 13 + 13 = 47
        // Suit sum: 1 + 2 + 4 + 3 + 4 = 14
        // Full House multiplier = 7
        // Score = 47 * 14 * 7 = 4606
        assertEquals(HandType.FULL_HOUSE, evaluator.evaluate(cards))
        assertEquals(4606, evaluator.calculateScore(cards))
    }

    @Test
    fun testFourOfAKindScoring() {
        val cards = listOf(
            Card(Suit.CLUBS, Rank.NINE),
            Card(Suit.DIAMONDS, Rank.NINE),
            Card(Suit.HEARTS, Rank.NINE),
            Card(Suit.SPADES, Rank.NINE),
            Card(Suit.SPADES, Rank.ACE)
        )
        // Target: Four Nines
        // Rank sum: 9 * 4 = 36
        // Suit sum: 1 + 2 + 3 + 4 = 10
        // Four of a Kind multiplier = 8
        // Score = 36 * 10 * 8 = 2880
        assertEquals(HandType.FOUR_OF_A_KIND, evaluator.evaluate(cards))
        assertEquals(2880, evaluator.calculateScore(cards))
    }

    @Test
    fun testStraightFlushScoring() {
        val cards = listOf(
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FIVE),
            Card(Suit.CLUBS, Rank.SIX),
            Card(Suit.CLUBS, Rank.SEVEN),
            Card(Suit.CLUBS, Rank.EIGHT)
        )
        // Rank sum: 4 + 5 + 6 + 7 + 8 = 30
        // Suit sum: 1 * 5 = 5
        // Straight Flush multiplier = 9
        // Score = 30 * 5 * 9 = 1350
        assertEquals(HandType.STRAIGHT_FLUSH, evaluator.evaluate(cards))
        assertEquals(1350, evaluator.calculateScore(cards))
    }

    @Test
    fun testRoyalFlushScoring() {
        val cards = listOf(
            Card(Suit.SPADES, Rank.TEN),
            Card(Suit.SPADES, Rank.JACK),
            Card(Suit.SPADES, Rank.QUEEN),
            Card(Suit.SPADES, Rank.KING),
            Card(Suit.SPADES, Rank.ACE)
        )
        // Rank sum: 10 + 11 + 12 + 13 + 14 = 60
        // Suit sum: 4 * 5 = 20
        // Royal Flush multiplier = 10
        // Score = 60 * 20 * 10 = 12000
        assertEquals(HandType.ROYAL_FLUSH, evaluator.evaluate(cards))
        assertEquals(12000, evaluator.calculateScore(cards))
    }

    @Test
    fun testLowAceStraight() {
        val cards = listOf(
            Card(Suit.SPADES, Rank.TWO),
            Card(Suit.DIAMONDS, Rank.THREE),
            Card(Suit.HEARTS, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FIVE),
            Card(Suit.SPADES, Rank.ACE)
        )
        // A, 2, 3, 4, 5 forms a straight
        assertEquals(HandType.STRAIGHT, evaluator.evaluate(cards))
    }

    @Test
    fun testSmallerHandsScoring() {
        // Test 1 card (High Card)
        val oneCard = listOf(Card(Suit.SPADES, Rank.ACE))
        assertEquals(HandType.HIGH_CARD, evaluator.evaluate(oneCard))
        assertEquals(14 * 4 * 1, evaluator.calculateScore(oneCard))

        // Test 2 cards (One Pair)
        val pair = listOf(
            Card(Suit.SPADES, Rank.ACE),
            Card(Suit.HEARTS, Rank.ACE)
        )
        assertEquals(HandType.ONE_PAIR, evaluator.evaluate(pair))
        assertEquals((14 + 14) * (4 + 3) * 2, evaluator.calculateScore(pair))

        // Test 3 cards (Straight Flush)
        val straightFlush3 = listOf(
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.CLUBS, Rank.THREE),
            Card(Suit.CLUBS, Rank.ACE)
        )
        assertEquals(HandType.STRAIGHT_FLUSH, evaluator.evaluate(straightFlush3))
    }

    @Test
    fun testEvaluateBestHandLargePool() {
        // Prepare 7 cards, including a Spades Royal Flush and some smaller cards
        val pool = listOf(
            Card(Suit.SPADES, Rank.TEN),
            Card(Suit.SPADES, Rank.JACK),
            Card(Suit.SPADES, Rank.QUEEN),
            Card(Suit.SPADES, Rank.KING),
            Card(Suit.SPADES, Rank.ACE),
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.DIAMONDS, Rank.THREE)
        )
        val result = evaluator.evaluateBestHand(pool)
        assertEquals(HandType.ROYAL_FLUSH, result.type)
        assertEquals(12000, result.score)
        assertTrue(result.cards.contains(Card(Suit.SPADES, Rank.ACE)))
    }
}
