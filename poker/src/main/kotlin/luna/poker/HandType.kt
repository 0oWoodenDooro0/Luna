package luna.poker

enum class HandType(val displayName: String, val multiplier: Int) {
    ROYAL_FLUSH("皇家同花順", 10),
    STRAIGHT_FLUSH("同花順", 9),
    FOUR_OF_A_KIND("四條", 8),
    FULL_HOUSE("葫蘆", 7),
    FLUSH("同花", 6),
    STRAIGHT("順子", 5),
    THREE_OF_A_KIND("三條", 4),
    TWO_PAIR("兩對", 3),
    ONE_PAIR("對子", 2),
    HIGH_CARD("散牌", 1);
}
