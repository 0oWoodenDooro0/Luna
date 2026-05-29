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
        responseText.append("抽到的手牌：${drawnCards.joinToString(" ") { getCardEmojiString(it) }}\n")

        if (countOption == 5) {
            val handType = evaluator.evaluate(drawnCards)
            val score = evaluator.calculateScore(drawnCards)
            responseText.append("手牌牌型：**${handType.displayName}** (乘數: x${handType.multiplier})\n")
            responseText.append("獲得分數：**$score 分** 🏆\n")
        } else {
            // Calculate simple score for non-5 cards
            val baseScore = drawnCards.sumOf { it.rank.score * it.suit.score }
            responseText.append("獲得分數：**$baseScore 分** (非 5 張手牌，以單卡基本分累加)\n")
            responseText.append("💡 *提示：抽滿 5 張牌即可觸發完整的撲克牌型加成計算喔！*\n")
        }

        val response = interaction.deferPublicResponse()
        response.respond {
            content = responseText.toString()
        }
    }

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
