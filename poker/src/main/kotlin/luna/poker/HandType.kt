package luna.poker

enum class HandType(val displayName: String, val multiplier: Int) {
    HIGH_CARD("散牌", 1),
    ONE_PAIR("對子", 2),
    TWO_PAIR("兩對", 3),
    THREE_OF_A_KIND("三條", 4),
    FULL_HOUSE("葫蘆", 5),
    FLUSH("同花", 6),
    STRAIGHT("順子", 7),
    THREE_PAIR("三對", 8),
    FOUR_OF_A_KIND("四條", 9),
    SIX_CARD_STRAIGHT("6張順子", 10),
    SIX_CARD_FLUSH("6張同花", 11),
    TWO_THREE_OF_A_KIND("雙三條", 12),
    SEVEN_CARD_STRAIGHT("7張順子", 13),
    STRAIGHT_FLUSH("同花順", 14),
    SEVEN_CARD_FLUSH("7張同花", 15),
    FULLER_HOUSE("超級葫蘆", 16),
    FIVE_OF_A_KIND("五條", 17),
    ROYAL_FLUSH("皇家同花順", 18),
    SIX_CARD_STRAIGHT_FLUSH("6張同花順", 19),
    SIX_OF_A_KIND("六條", 20),
    SEVEN_CARD_STRAIGHT_FLUSH("7張同花順", 21),
    SEVEN_OF_A_KIND("七條", 22);
}
