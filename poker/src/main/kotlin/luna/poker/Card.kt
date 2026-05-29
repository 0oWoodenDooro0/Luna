package luna.poker

enum class Suit(val symbol: String, val displayName: String, val score: Int) {
    SPADES("♠", "黑桃", 4),
    HEARTS("♥", "紅心", 3),
    DIAMONDS("♦", "方塊", 2),
    CLUBS("♣", "梅花", 1);

    override fun toString(): String = symbol
}

enum class Rank(val symbol: String, val value: Int, val displayName: String) {
    TWO("2", 2, "2"),
    THREE("3", 3, "3"),
    FOUR("4", 4, "4"),
    FIVE("5", 5, "5"),
    SIX("6", 6, "6"),
    SEVEN("7", 7, "7"),
    EIGHT("8", 8, "8"),
    NINE("9", 9, "9"),
    TEN("10", 10, "10"),
    JACK("J", 11, "J"),
    QUEEN("Q", 12, "Q"),
    KING("K", 13, "K"),
    ACE("A", 14, "A");

    val score: Int
        get() = value

    override fun toString(): String = symbol
}

data class Card(val suit: Suit, val rank: Rank) {
    override fun toString(): String = "${rank}${suit}"
    
    val displayName: String
        get() = "${suit.displayName}${rank.displayName}"
}
