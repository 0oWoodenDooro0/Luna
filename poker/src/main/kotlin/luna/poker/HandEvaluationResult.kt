package luna.poker

data class HandEvaluationResult(
    val cards: List<Card>,
    val type: HandType,
    val score: Int
)
