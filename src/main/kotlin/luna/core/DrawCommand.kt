package luna.core

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.integer
import luna.poker.Card
import luna.poker.Deck
import luna.poker.HandEvaluator
import java.util.concurrent.ConcurrentHashMap

class DrawCommand : Command {
    override val name = "抽牌"
    override val description = "從你的個人撲克牌組中抽取卡牌並計算得分"

    private val evaluator = HandEvaluator()

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            integer("張數", "要抽的卡牌張數 (預設為 5，範圍 1-52)") {
                required = false
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id
        val username = interaction.user.username

        // Read options
        val countOption = interaction.command.integers["張數"]?.toInt() ?: 5

        if (countOption < 1 || countOption > 52) {
            val response = interaction.deferEphemeralResponse()
            response.respond {
                content = "抽牌張數必須在 1 到 52 之間！"
            }
            return
        }

        // Create and shuffle a fresh standard 52-card deck every time
        val deck = Deck.standard52()
        deck.shuffle()

        val drawnCards = deck.draw(countOption)

        // Calculate score
        val responseText = StringBuilder()
        responseText.append("♠️ ♥️ ♦️ ♣️ **撲克抽牌結果** ♠️ ♥️ ♦️ ♣️\n\n")
        responseText.append("玩家：<@$userId> ($username)\n")
        responseText.append("抽到的手牌 (${drawnCards.size} 張)：${drawnCards.joinToString(" ") { getCardEmojiString(it) }}\n\n")

        if (countOption >= 5) {
            // Heuristic to select at most 15 cards to keep combinations small and fast (max 3003 combinations)
            val cardsToEvaluate =
                if (drawnCards.size > 15) {
                    val bySuit = drawnCards.groupBy { it.suit }
                    val flushCandidateSuit = bySuit.maxByOrNull { it.value.size }?.key
                    val bestCards = mutableSetOf<Card>()

                    if (flushCandidateSuit != null) {
                        bestCards.addAll(bySuit[flushCandidateSuit] ?: emptyList())
                    }

                    val sortedByRank = drawnCards.sortedWith(compareByDescending<Card> { it.rank.score }.thenByDescending { it.suit.score })
                    for (card in sortedByRank) {
                        if (bestCards.size >= 15) break
                        bestCards.add(card)
                    }
                    bestCards.toList()
                } else {
                    drawnCards
                }

            val combinations = getCombinations(cardsToEvaluate, 5)
            val bestHand =
                combinations
                    .map { combo ->
                        val type = evaluator.evaluate(combo)
                        val score = evaluator.calculateScore(combo)
                        BestHandInfo(combo, type, score)
                    }.maxWithOrNull(
                        compareBy<BestHandInfo> { it.type.multiplier }
                            .thenBy { it.score },
                    )

            if (bestHand != null) {
                if (countOption > 5) {
                    responseText.append("🏆 **最佳 5 張組合**：${bestHand.cards.joinToString(" ") { getCardEmojiString(it) }}\n")
                }
                responseText.append("手牌牌型：**${bestHand.type.displayName}** (乘數: x${bestHand.type.multiplier})\n")
                responseText.append("獲得分數：**${bestHand.score} 分** 🏆\n")
            }
        } else {
            // Calculate simple score for less than 5 cards
            val baseScore = drawnCards.sumOf { it.rank.score * it.suit.score }
            responseText.append("獲得分數：**$baseScore 分** (非 5 張手牌，以單卡基本分累加)\n")
            responseText.append("💡 *提示：抽滿 5 張牌以上即可觸發完整的最佳撲克牌型加成計算喔！*\n")
        }

        val response = interaction.deferPublicResponse()
        response.respond {
            content = responseText.toString()
        }
    }

    private fun <T> getCombinations(
        list: List<T>,
        k: Int,
    ): List<List<T>> {
        val result = mutableListOf<List<T>>()

        fun helper(
            start: Int,
            current: MutableList<T>,
        ) {
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

    private data class BestHandInfo(
        val cards: List<Card>,
        val type: luna.poker.HandType,
        val score: Int,
    )

    private fun getCardEmojiString(card: Card): String {
        val suitEmoji =
            when (card.suit) {
                luna.poker.Suit.SPADES -> "♠️"
                luna.poker.Suit.HEARTS -> "♥️"
                luna.poker.Suit.DIAMONDS -> "♦️"
                luna.poker.Suit.CLUBS -> "♣️"
            }
        return "`$suitEmoji ${card.rank.symbol}`"
    }
}
