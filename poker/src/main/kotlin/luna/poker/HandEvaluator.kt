package luna.poker

class HandEvaluator {

    /**
     * Evaluates a 5-card hand and returns its [HandType].
     */
    fun evaluate(cards: List<Card>): HandType {
        require(cards.size == 5) { "A poker hand must consist of exactly 5 cards, got ${cards.size}" }

        val isFlush = cards.map { it.suit }.distinct().size == 1
        val ranks = cards.map { it.rank }.sortedBy { it.value }
        
        val isStraight = isStraight(ranks)
        
        if (isFlush && isStraight) {
            // Check if it is Royal Flush (Straight starting from 10, or containing 10, J, Q, K, A)
            val isRoyal = ranks.any { it == Rank.TEN } && ranks.any { it == Rank.ACE }
            return if (isRoyal) HandType.ROYAL_FLUSH else HandType.STRAIGHT_FLUSH
        }

        val rankGroups = cards.groupBy { it.rank }
        val groupSizes = rankGroups.values.map { it.size }.sortedDescending()

        return when {
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

    private fun isStraight(sortedRanks: List<Rank>): Boolean {
        // Standard Straight Check
        val isStandard = (0..3).all { sortedRanks[it + 1].value == sortedRanks[it].value + 1 }
        if (isStandard) return true

        // Low Ace Straight Check (A, 2, 3, 4, 5) -> rank values: 2, 3, 4, 5, 14
        val lowAceValues = listOf(Rank.TWO, Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.ACE)
        return sortedRanks.size == 5 && sortedRanks.containsAll(lowAceValues)
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
                // All 5 cards are involved
                cards
            }
            HandType.FOUR_OF_A_KIND -> {
                // The 4 cards with matching rank
                rankGroups.values.first { it.size == 4 }
            }
            HandType.THREE_OF_A_KIND -> {
                // The 3 cards with matching rank
                rankGroups.values.first { it.size == 3 }
            }
            HandType.TWO_PAIR -> {
                // The 4 cards making up the two pairs
                rankGroups.values.filter { it.size == 2 }.flatten()
            }
            HandType.ONE_PAIR -> {
                // The 2 cards making up the pair
                rankGroups.values.first { it.size == 2 }
            }
        }
    }
}
