package luna.poker

enum class HandType(val displayName: String, val baseScore: Int, val multiplier: Int) {
    HIGH_CARD("散牌", 0, 1),
    ONE_PAIR("對子", 1000, 2),
    TWO_PAIR("兩對", 2000, 3),
    THREE_OF_A_KIND("三條", 3000, 4),
    FULL_HOUSE("葫蘆", 4000, 5),
    FLUSH("同花", 5000, 6),
    STRAIGHT("順子", 6000, 7),
    THREE_PAIR("三對", 7000, 8),
    FOUR_OF_A_KIND("四條", 8000, 9),
    SIX_CARD_STRAIGHT("6張順子", 9000, 10),
    SIX_CARD_FLUSH("6張同花", 10000, 11),
    TWO_THREE_OF_A_KIND("雙三條", 11000, 12),
    SEVEN_CARD_STRAIGHT("7張順子", 12000, 13),
    STRAIGHT_FLUSH("同花順", 13000, 14),
    SEVEN_CARD_FLUSH("7張同花", 14000, 15),
    FULLER_HOUSE("超級葫蘆", 15000, 16),
    FIVE_OF_A_KIND("五條", 16000, 17),
    ROYAL_FLUSH("皇家同花順", 17000, 18),
    SIX_CARD_STRAIGHT_FLUSH("6張同花順", 18000, 19),
    SIX_OF_A_KIND("六條", 19000, 20),
    SEVEN_CARD_STRAIGHT_FLUSH("7張同花順", 20000, 21),
    SEVEN_OF_A_KIND("七條", 21000, 22);
}
