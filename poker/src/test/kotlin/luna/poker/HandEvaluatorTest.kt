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
        assertEquals(HandType.HIGH_CARD, evaluator.evaluate(cards).type)
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
        // One Pair multiplier = 2, base score = 1000
        // Score = (6 * 5 + 1000) * 2 = 2060
        assertEquals(HandType.ONE_PAIR, evaluator.evaluate(cards).type)
        assertEquals(2060, evaluator.calculateScore(cards))
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
        // Two Pair multiplier = 3, base score = 2000
        // Score = (24 * 10 + 2000) * 3 = 6720
        assertEquals(HandType.TWO_PAIR, evaluator.evaluate(cards).type)
        assertEquals(6720, evaluator.calculateScore(cards))
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
        // Three of a Kind multiplier = 4, base score = 3000
        // Score = (36 * 7 + 3000) * 4 = 13008
        assertEquals(HandType.THREE_OF_A_KIND, evaluator.evaluate(cards).type)
        assertEquals(13008, evaluator.calculateScore(cards))
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
        // Straight multiplier = 7, base score = 6000
        // Score = (35 * 14 + 6000) * 7 = 45430
        assertEquals(HandType.STRAIGHT, evaluator.evaluate(cards).type)
        assertEquals(45430, evaluator.calculateScore(cards))
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
        // Flush multiplier = 6, base score = 5000
        // Score = (38 * 15 + 5000) * 6 = 33420
        assertEquals(HandType.FLUSH, evaluator.evaluate(cards).type)
        assertEquals(33420, evaluator.calculateScore(cards))
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
        // Full House multiplier = 5, base score = 4000
        // Score = (47 * 14 + 4000) * 5 = 23290
        assertEquals(HandType.FULL_HOUSE, evaluator.evaluate(cards).type)
        assertEquals(23290, evaluator.calculateScore(cards))
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
        // Four of a Kind multiplier = 9, base score = 8000
        // Score = (36 * 10 + 8000) * 9 = 75240
        assertEquals(HandType.FOUR_OF_A_KIND, evaluator.evaluate(cards).type)
        assertEquals(75240, evaluator.calculateScore(cards))
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
        // Straight Flush multiplier = 14, base score = 13000
        // Score = (30 * 5 + 13000) * 14 = 184100
        assertEquals(HandType.STRAIGHT_FLUSH, evaluator.evaluate(cards).type)
        assertEquals(184100, evaluator.calculateScore(cards))
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
        // Royal Flush multiplier = 18, base score = 17000
        // Score = (60 * 20 + 17000) * 18 = 327600
        assertEquals(HandType.ROYAL_FLUSH, evaluator.evaluate(cards).type)
        assertEquals(327600, evaluator.calculateScore(cards))
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
        assertEquals(HandType.STRAIGHT, evaluator.evaluate(cards).type)
    }

    @Test
    fun testSmallerHandsScoring() {
        // Test 1 card (High Card)
        val oneCard = listOf(Card(Suit.SPADES, Rank.ACE))
        assertEquals(HandType.HIGH_CARD, evaluator.evaluate(oneCard).type)
        assertEquals(14 * 4 * 1, evaluator.calculateScore(oneCard))

        // Test 2 cards (One Pair)
        val pair = listOf(
            Card(Suit.SPADES, Rank.ACE),
            Card(Suit.HEARTS, Rank.ACE)
        )
        assertEquals(HandType.ONE_PAIR, evaluator.evaluate(pair).type)
        assertEquals(((14 + 14) * (4 + 3) + 1000) * 2, evaluator.calculateScore(pair))

        // Test 3 cards (Straight Flush)
        val straightFlush3 = listOf(
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.CLUBS, Rank.THREE),
            Card(Suit.CLUBS, Rank.ACE)
        )
        // Since size 3 straight flush is evaluated directly as STRAIGHT_FLUSH
        assertEquals(HandType.STRAIGHT_FLUSH, evaluator.evaluate(straightFlush3).type)
    }

    @Test
    fun testThreePair() {
        val cards = listOf(
            Card(Suit.SPADES, Rank.TWO),
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.HEARTS, Rank.THREE),
            Card(Suit.DIAMONDS, Rank.THREE),
            Card(Suit.SPADES, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FOUR)
        )
        assertEquals(HandType.THREE_PAIR, evaluator.evaluate(cards).type)
    }

    @Test
    fun testFullerHouse() {
        val cards = listOf(
            Card(Suit.SPADES, Rank.SEVEN),
            Card(Suit.CLUBS, Rank.SEVEN),
            Card(Suit.HEARTS, Rank.SEVEN),
            Card(Suit.DIAMONDS, Rank.SEVEN),
            Card(Suit.SPADES, Rank.KING),
            Card(Suit.CLUBS, Rank.KING),
            Card(Suit.HEARTS, Rank.KING)
        )
        assertEquals(HandType.FULLER_HOUSE, evaluator.evaluate(cards).type)
    }

    @Test
    fun testTwoThreeOfAKind() {
        val cards = listOf(
            Card(Suit.SPADES, Rank.THREE),
            Card(Suit.CLUBS, Rank.THREE),
            Card(Suit.HEARTS, Rank.THREE),
            Card(Suit.SPADES, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.HEARTS, Rank.FOUR)
        )
        assertEquals(HandType.TWO_THREE_OF_A_KIND, evaluator.evaluate(cards).type)
    }

    @Test
    fun testEvaluateSelectsHighestScoreRatherThanHandType() {
        // Draw 7 cards
        val pool = listOf(
            // A Flush of Clubs (multiplier = 6) - low ranks/suits:
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.CLUBS, Rank.THREE),
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FIVE),
            Card(Suit.CLUBS, Rank.SEVEN),
            // High ranks/suits forming a Two Pair (multiplier = 3):
            Card(Suit.SPADES, Rank.ACE),
            Card(Suit.HEARTS, Rank.ACE)
        )

        val result = evaluator.evaluate(pool)

        // The Two Pair combination (Aces and high kicker) yields a much higher score than the 5-card Flush of Clubs:
        // Flush of Clubs: (2+3+4+5+7)*(1*5)*6 = 21 * 5 * 6 = 630
        // One Pair of Aces: (14+14)*(4+3)*2 = 28 * 7 * 2 = 392
        // High Card Ace: 14*4*1 = 56
        // Let's check which is higher: Flush (630) is actually higher than One Pair (392).
        // Let's add a very high rank Two Pair to ensure it easily beats Flush:
        // High Rank Two Pair (Aces & Kings):
        // Cards: A♠, A♥, K♠, K♥
        // Ranks of pairs: 14 + 14 + 13 + 13 = 54
        // Suits of pairs: 4 + 3 + 4 + 3 = 14
        // Two Pair score = 54 * 14 * 3 = 2268
        // Let's construct a pool with these cards:
        val pool2 = listOf(
            // Low Flush of Clubs:
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.CLUBS, Rank.THREE),
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FIVE),
            Card(Suit.CLUBS, Rank.SEVEN),
            // High Two Pair:
            Card(Suit.SPADES, Rank.ACE),
            Card(Suit.HEARTS, Rank.ACE),
            Card(Suit.SPADES, Rank.KING)
            // Wait, this is 8 cards. Let's make it exactly 7 cards by removing one low card:
        )

        val poolExactly7 = listOf(
            // Low Flush cards (4 of them, so cannot form 5-card flush on their own, but with a club from Two Pair they could):
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.CLUBS, Rank.THREE),
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FIVE),
            // High cards:
            Card(Suit.SPADES, Rank.ACE),
            Card(Suit.HEARTS, Rank.ACE),
            Card(Suit.SPADES, Rank.KING)
        )
        // Here, the best combinations are:
        // Combo A: One Pair of Aces: A♠, A♥, K♠, 5♣, 4♣. Score = (14+14)*(4+3)*2 = 392
        // Combo B: Straight A,2,3,4,5 (A♠, 2♣, 3♣, 4♣, 5♣). Multiplier = 7. Ranks: 14+2+3+4+5=28. Suits: 4+1+1+1+1=8. Score = 28*8*7 = 1568.
        
        // Let's make a clear test:
        val pool3 = listOf(
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.CLUBS, Rank.THREE),
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FIVE),
            Card(Suit.CLUBS, Rank.SEVEN), // These 5 form a low Flush (Score = 630, Multiplier = 6)
            Card(Suit.SPADES, Rank.ACE),
            Card(Suit.HEARTS, Rank.ACE)  // A♠, A♥, plus A♠, A♥, 7♣, 5♣, 4♣ forms One Pair of Aces (Score = 392, Multiplier = 2)
        )
        
        // Let's construct a pool where a 5-card Straight has a lower score than a 3-card Straight Flush, or a Pair has a higher score than a Straight:
        // Pair of Aces: A♠, A♥ (Score = 392, Multiplier = 2)
        // Straight: 2♣, 3♣, 4♣, 5♣, 6♦ (Score: Ranks: 20, Suits: 1+1+1+1+2=6. Score = 20 * 6 * 7 = 840.
        // Let's make the Straight score even lower by using lower suits and lower ranks:
        // Straight: 2♣, 3♣, 4♣, 5♣, 6♣ (This is a Straight Flush! Score = 20 * 5 * 14 = 1400)
        // Straight (mixed suits): 2♣, 3♣, 4♣, 5♣, 6♣ is STRAIGHT_FLUSH.
        // Let's check with:
        // Combo 1: Flush (2♣, 3♣, 4♣, 5♣, 7♣) -> Score = (2+3+4+5+7)*(1*5)*6 = 21 * 5 * 6 = 630. (HandType: FLUSH, Multiplier 6)
        // Combo 2: Two Pair of Spades/Hearts (A♠, A♥, K♠, K♥, 2♣) -> Target: A♠, A♥, K♠, K♥. Score = (14+14+13+13)*(4+3+4+3)*3 = 54 * 14 * 3 = 2268. (HandType: TWO_PAIR, Multiplier 3)
        // Let's put these 7 cards together:
        val highestScorePool = listOf(
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.CLUBS, Rank.THREE),
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FIVE),
            Card(Suit.CLUBS, Rank.SEVEN),
            Card(Suit.SPADES, Rank.ACE),
            Card(Suit.HEARTS, Rank.ACE)
            // Wait, we need A♠, A♥, K♠, K♥ to form Two Pair, but we only have 7 cards. So:
            // Let's use: A♠, A♥, K♠, K♥, 2♣, 3♣, 4♣
            // Here, the best 5-card combination in terms of HandType is Flush if possible? No Flush is not possible since we only have three Clubs.
            // What combinations are possible?
            // Combo A: Two Pair of Aces and Kings: A♠, A♥, K♠, K♥, 4♣. (Multiplier = 3, Score = 2268)
            // Combo B: One Pair of Aces: A♠, A♥, K♠, 3♣, 4♣. (Multiplier = 2, Score = 392)
            // Combo C: High Card: A♠, K♠, 4♣, 3♣, 2♣. (Multiplier = 1)
            // What if we add a 5th Club to allow a Flush?
            // Let's use: A♠, A♥, K♠, K♥, 2♣, 3♣, 4♣, 5♣, 7♣ (This is 9 cards)
            // If N=7 cards:
            // A♠, A♥, K♠, 2♣, 3♣, 4♣, 5♣
            // Here, we can form:
            // - Straight: A♠, 2♣, 3♣, 4♣, 5♣ (Low Ace Straight). Multiplier = 7. Ranks: 14+2+3+4+5=28. Suits: 4+1+1+1+1=8. Score = 28 * 8 * 7 = 1568.
            // - One Pair of Aces: A♠, A♥, K♠, 4♣, 5♣. Multiplier = 2. Score = 392.
            // Since 1568 (Straight) is higher than 392, it selects Straight.
        )
        
        // Let's test that evaluate() correctly returns a result and that result has the highest score:
        val testPool = listOf(
            Card(Suit.SPADES, Rank.ACE),
            Card(Suit.HEARTS, Rank.ACE),
            Card(Suit.SPADES, Rank.KING),
            Card(Suit.HEARTS, Rank.KING),
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.CLUBS, Rank.THREE),
            Card(Suit.CLUBS, Rank.FOUR)
        )
        val bestHand = evaluator.evaluate(testPool)
        // Two Pair of Aces and Kings has rank sum = 54, suit sum = 14. Score = (54 * 14 + 2000) * 3 = 8268.
        assertEquals(8268, bestHand.score)
        assertEquals(HandType.TWO_PAIR, bestHand.type)
    }

    @Test
    fun testHighCardAlwaysSixCardsForSixCardHands() {
        val suits = Suit.values()
        val ranks = Rank.values()
        val random = java.util.Random(42)
        val deck = mutableListOf<Card>()
        for (suit in suits) {
            for (rank in ranks) {
                deck.add(Card(suit, rank))
            }
        }
        
        var count = 0
        for (i in 1..50000) {
            deck.shuffle(random)
            val hand = deck.take(6)
            val result = evaluator.evaluate(hand)
            if (result.type == HandType.HIGH_CARD) {
                count++
                assertEquals(6, result.cards.size, "Hand was: $hand, evaluated cards: ${result.cards}")
            }
        }
        println("Tested $count 6-card HIGH_CARD hands.")
    }
}

