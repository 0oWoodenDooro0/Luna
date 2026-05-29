package luna.poker

class HandEvaluator {

    /**
     * Evaluates a hand of 1 to 5 cards and returns its [HandType].
     */
    fun evaluate(cards: List<Card>): HandType {
        require(cards.size in 1..5) { "A poker hand must consist of 1 to 5 cards, got ${cards.size}" }

        val size = cards.size
        if (size == 1) return HandType.HIGH_CARD

        val isFlush = cards.map { it.suit }.distinct().size == 1
        val ranks = cards.map { it.rank }.sortedBy { it.value }

        val isStraight = isStraight(ranks)

        if (isFlush && isStraight) {
            // Royal Flush check: must contain Ace and the lowest rank of the straight must match the straight size
            val isRoyal = ranks.contains(Rank.ACE) && ranks.first().value == Rank.ACE.value - size + 1
            return if (isRoyal) HandType.ROYAL_FLUSH else HandType.STRAIGHT_FLUSH
        }

        val rankGroups = cards.groupBy { it.rank }
        val groupSizes = rankGroups.values.map { it.size }.sortedDescending()

        return when {
            groupSizes[0] == 4 -> HandType.FOUR_OF_A_KIND
            groupSizes[0] == 3 && (groupSizes.size > 1 && groupSizes[1] == 2) -> HandType.FULL_HOUSE
            isFlush -> HandType.FLUSH
            isStraight -> HandType.STRAIGHT
            groupSizes[0] == 3 -> HandType.THREE_OF_A_KIND
            groupSizes[0] == 2 && (groupSizes.size > 1 && groupSizes[1] == 2) -> HandType.TWO_PAIR
            groupSizes[0] == 2 -> HandType.ONE_PAIR
            else -> HandType.HIGH_CARD
        }
    }

    /**
     * Identifies the HandType and calculates the score based on the target cards.
     */
    fun calculateScore(cards: List<Card>): Int {
        val handType = evaluate(cards)
        val targetCards = getTargetCards(cards, handType)

        val sumRanks = targetCards.sumOf { it.rank.score }
        val sumSuits = targetCards.sumOf { it.suit.score }
        val baseScore = sumRanks * sumSuits

        return baseScore * handType.multiplier
    }

    /**
     * Evaluates a hand of any size.
     * - If N >= 5: finds the best 5-card combination.
     * - If N < 5: evaluates the N-card combination directly.
     */
    fun evaluateBestHand(cards: List<Card>): HandEvaluationResult {
        require(cards.isNotEmpty()) { "Cannot evaluate an empty list of cards" }

        if (cards.size < 5) {
            val handType = evaluate(cards)
            val score = calculateScore(cards)
            return HandEvaluationResult(cards, handType, score)
        }

        // Select top 6 cards of each suit to keep combination size at most 24 (42,504 combinations max), guaranteeing the best possible hand is found.
        val cardsToEvaluate = if (cards.size > 24) {
            val bySuit = cards.groupBy { it.suit }
            val bestCards = mutableSetOf<Card>()
            for (suitCards in bySuit.values) {
                bestCards.addAll(suitCards.sortedByDescending { it.rank.score }.take(6))
            }
            bestCards.toList()
        } else {
            cards
        }

        val combinations = getCombinations(cardsToEvaluate, 5)
        val bestHand = combinations.map { combo ->
            val type = evaluate(combo)
            val score = calculateScore(combo)
            HandEvaluationResult(combo, type, score)
        }.maxWithOrNull(
            compareBy<HandEvaluationResult> { it.type.multiplier }
                .thenBy { it.score }
        ) ?: throw IllegalStateException("Could not find any hand combinations")

        return bestHand
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

    /**
     * Extracts the relevant target cards used to calculate the score for each hand type.
     */
    private fun getTargetCards(cards: List<Card>, handType: HandType): List<Card> {
        val rankGroups = cards.groupBy { it.rank }

        return when (handType) {
            HandType.ROYAL_FLUSH,
            HandType.STRAIGHT_FLUSH,
            HandType.FULL_HOUSE,
            HandType.FLUSH,
            HandType.STRAIGHT,
            HandType.HIGH_CARD -> {
                cards
            }
            HandType.FOUR_OF_A_KIND -> {
                rankGroups.values.first { it.size == 4 }
            }
            HandType.THREE_OF_A_KIND -> {
                rankGroups.values.first { it.size == 3 }
            }
            HandType.TWO_PAIR -> {
                rankGroups.values.filter { it.size == 2 }.flatten()
            }
            HandType.ONE_PAIR -> {
                rankGroups.values.first { it.size == 2 }
            }
        }
    }
}
