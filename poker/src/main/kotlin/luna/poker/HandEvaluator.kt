package luna.poker

class HandEvaluator {

    /**
     * Evaluates a hand of any size (1 to N) and returns the [HandEvaluationResult] representing
     * the best possible combination (subset) of cards.
     * - If N < 5: evaluates the N-card hand directly.
     * - If N >= 5: evaluates combinations of sizes 5 to N.
     */
    fun evaluate(cards: List<Card>): HandEvaluationResult {
        require(cards.isNotEmpty()) { "Cannot evaluate an empty list of cards" }

        val combinations = if (cards.size < 5) {
            listOf(cards)
        } else {
            (5..cards.size).flatMap { k ->
                getCombinations(cards, k)
            }
        }

        val bestHand = combinations.map { combo ->
            val type = evaluateDirect(combo)
            val score = calculateScoreDirect(combo, type)
            HandEvaluationResult(combo, type, score)
        }.maxWithOrNull(
            compareBy<HandEvaluationResult> { it.score }
                .thenBy { it.type.multiplier }
        ) ?: throw IllegalStateException("Could not find any hand combinations")

        return bestHand
    }

    /**
     * Helper to calculate the score of a list of cards.
     */
    fun calculateScore(cards: List<Card>): Int {
        return evaluate(cards).score
    }

    private fun evaluateDirect(cards: List<Card>): HandType {
        require(cards.size in 1..7) { "A direct poker hand must consist of 1 to 7 cards, got ${cards.size}" }

        val size = cards.size
        if (size == 1) return HandType.HIGH_CARD

        val isFlush = cards.map { it.suit }.distinct().size == 1
        val ranks = cards.map { it.rank }.sortedBy { it.value }
        val isStraight = isStraight(ranks)
        val rankGroups = cards.groupBy { it.rank }

        if (size == 7) {
            val groupSizes = rankGroups.values.map { it.size }.sortedDescending()
            return when {
                groupSizes[0] == 7 -> HandType.SEVEN_OF_A_KIND
                isFlush && isStraight -> HandType.SEVEN_CARD_STRAIGHT_FLUSH
                groupSizes[0] == 6 -> HandType.SIX_OF_A_KIND
                groupSizes[0] == 5 && groupSizes[1] == 2 -> HandType.FULLER_HOUSE
                groupSizes[0] == 5 -> HandType.FIVE_OF_A_KIND
                groupSizes[0] == 4 && groupSizes[1] == 3 -> HandType.FULLER_HOUSE
                groupSizes[0] == 4 -> HandType.FOUR_OF_A_KIND
                isFlush -> HandType.SEVEN_CARD_FLUSH
                isStraight -> HandType.SEVEN_CARD_STRAIGHT
                groupSizes[0] == 3 && groupSizes[1] == 3 -> HandType.TWO_THREE_OF_A_KIND
                groupSizes[0] == 3 && groupSizes[1] == 2 -> HandType.FULL_HOUSE
                groupSizes[0] == 3 -> HandType.THREE_OF_A_KIND
                groupSizes[0] == 2 && groupSizes[1] == 2 && groupSizes[2] == 2 -> HandType.THREE_PAIR
                groupSizes[0] == 2 && groupSizes[1] == 2 -> HandType.TWO_PAIR
                groupSizes[0] == 2 -> HandType.ONE_PAIR
                else -> HandType.HIGH_CARD
            }
        }

        if (size == 6) {
            val groupSizes = rankGroups.values.map { it.size }.sortedDescending()
            return when {
                groupSizes[0] == 6 -> HandType.SIX_OF_A_KIND
                isFlush && isStraight -> HandType.SIX_CARD_STRAIGHT_FLUSH
                groupSizes[0] == 5 -> HandType.FIVE_OF_A_KIND
                groupSizes[0] == 4 -> HandType.FOUR_OF_A_KIND
                groupSizes[0] == 3 && groupSizes[1] == 3 -> HandType.TWO_THREE_OF_A_KIND
                groupSizes[0] == 3 && groupSizes[1] == 2 -> HandType.FULL_HOUSE
                isFlush -> HandType.SIX_CARD_FLUSH
                isStraight -> HandType.SIX_CARD_STRAIGHT
                groupSizes[0] == 3 -> HandType.THREE_OF_A_KIND
                groupSizes[0] == 2 && groupSizes[1] == 2 && groupSizes[2] == 2 -> HandType.THREE_PAIR
                groupSizes[0] == 2 && groupSizes[1] == 2 -> HandType.TWO_PAIR
                groupSizes[0] == 2 -> HandType.ONE_PAIR
                else -> HandType.HIGH_CARD
            }
        }

        if (size == 5) {
            val groupSizes = rankGroups.values.map { it.size }.sortedDescending()
            return when {
                isFlush && isStraight -> {
                    val isRoyal = ranks.contains(Rank.ACE) && ranks.first().value == Rank.ACE.value - size + 1
                    if (isRoyal) HandType.ROYAL_FLUSH else HandType.STRAIGHT_FLUSH
                }
                groupSizes[0] == 5 -> HandType.FIVE_OF_A_KIND
                groupSizes[0] == 4 -> HandType.FOUR_OF_A_KIND
                groupSizes[0] == 3 && groupSizes[1] == 2 -> HandType.FULL_HOUSE
                isFlush -> HandType.FLUSH
                isStraight -> HandType.STRAIGHT
                groupSizes[0] == 3 -> HandType.THREE_OF_A_KIND
                groupSizes[0] == 2 && groupSizes[1] == 2 -> HandType.TWO_PAIR
                groupSizes[0] == 2 -> HandType.ONE_PAIR
                else -> HandType.HIGH_CARD
            }
        }

        // For size 2, 3, 4
        val groupSizes = rankGroups.values.map { it.size }.sortedDescending()
        return when {
            isFlush && isStraight -> HandType.STRAIGHT_FLUSH
            groupSizes[0] == 4 -> HandType.FOUR_OF_A_KIND
            groupSizes[0] == 3 -> HandType.THREE_OF_A_KIND
            isFlush -> HandType.FLUSH
            isStraight -> HandType.STRAIGHT
            groupSizes[0] == 2 && (groupSizes.size > 1 && groupSizes[1] == 2) -> HandType.TWO_PAIR
            groupSizes[0] == 2 -> HandType.ONE_PAIR
            else -> HandType.HIGH_CARD
        }
    }

    private fun calculateScoreDirect(cards: List<Card>, handType: HandType): Int {
        val targetCards = getTargetCards(cards, handType)

        val sumRanks = targetCards.sumOf { it.rank.score }
        val sumSuits = targetCards.sumOf { it.suit.score }
        val baseScore = sumRanks * sumSuits

        return baseScore * handType.multiplier
    }

    private fun <T> getCombinations(list: List<T>, k: Int): List<List<T>> {
        val result = mutableListOf<List<T>>()
        fun helper(start: Int, current: MutableList<T>) {
            if (current.size == k) {
                result.add(current.toList())
                return
            }
            for (i in start until list.size) {
                current.add(list[i])
                helper(i + 1, current)
                current.removeAt(current.size - 1)
            }
        }
        helper(0, mutableListOf())
        return result
    }

    private fun isStraight(sortedRanks: List<Rank>): Boolean {
        if (sortedRanks.size < 2) return false
        val size = sortedRanks.size
        // Standard Straight Check
        val isStandard = (0 until size - 1).all { sortedRanks[it + 1].value == sortedRanks[it].value + 1 }
        if (isStandard) return true

        // Low Ace Straight Check
        if (sortedRanks.contains(Rank.ACE)) {
            val withoutAce = sortedRanks.filter { it != Rank.ACE }
            val expectedRanks = when (size) {
                7 -> listOf(Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN)
                6 -> listOf(Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX)
                5 -> listOf(Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE)
                4 -> listOf(Rank.TWO, Rank.THREE, Rank.FOUR)
                3 -> listOf(Rank.TWO, Rank.THREE)
                2 -> listOf(Rank.TWO)
                else -> emptyList()
            }
            if (withoutAce.containsAll(expectedRanks) && withoutAce.size == expectedRanks.size) {
                return true
            }
        }
        return false
    }

    private fun getTargetCards(cards: List<Card>, handType: HandType): List<Card> {
        val rankGroups = cards.groupBy { it.rank }

        return when (handType) {
            HandType.SEVEN_OF_A_KIND,
            HandType.SEVEN_CARD_STRAIGHT_FLUSH,
            HandType.SIX_CARD_STRAIGHT_FLUSH,
            HandType.TWO_THREE_OF_A_KIND,
            HandType.THREE_PAIR,
            HandType.SEVEN_CARD_STRAIGHT,
            HandType.SEVEN_CARD_FLUSH,
            HandType.FULLER_HOUSE,
            HandType.SIX_CARD_FLUSH,
            HandType.SIX_CARD_STRAIGHT,
            HandType.ROYAL_FLUSH,
            HandType.STRAIGHT_FLUSH,
            HandType.FULL_HOUSE,
            HandType.FLUSH,
            HandType.STRAIGHT,
            HandType.HIGH_CARD -> {
                cards
            }
            HandType.SIX_OF_A_KIND -> {
                rankGroups.values.first { it.size >= 6 }.take(6)
            }
            HandType.FIVE_OF_A_KIND -> {
                rankGroups.values.first { it.size >= 5 }.take(5)
            }
            HandType.FOUR_OF_A_KIND -> {
                rankGroups.values.first { it.size >= 4 }.take(4)
            }
            HandType.THREE_OF_A_KIND -> {
                rankGroups.values.first { it.size >= 3 }.take(3)
            }
            HandType.TWO_PAIR -> {
                rankGroups.values.filter { it.size >= 2 }.flatten()
            }
            HandType.ONE_PAIR -> {
                rankGroups.values.first { it.size >= 2 }.take(2)
            }
        }
    }
}
