package luna.poker.command

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.integer
import luna.core.Command
import luna.poker.Card
import luna.poker.Deck
import luna.poker.HandEvaluator

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

        val pokerUser = luna.poker.User.getOrCreate(userId.toString())
        val userCards = pokerUser.deck.getCards()

        // Create a temporary deck using the user's cards, shuffle it, and draw cards
        val tempDeck = Deck(userCards)
        tempDeck.shuffle()
        val drawnCards = tempDeck.draw(countOption)

        // Calculate score using business logic in poker module
        val evaluation = evaluator.evaluate(drawnCards)

        val responseText = StringBuilder()
        responseText.append("♠️ ♥️ ♦️ ♣️ **撲克抽牌結果** ♠️ ♥️ ♦️ ♣️\n\n")
        responseText.append("玩家：<@$userId> ($username)\n")
        responseText.append("抽到的手牌 (${drawnCards.size} 張)：${drawnCards.joinToString(" ") { getCardEmojiString(it) }}\n\n")

        responseText.append("🏆 **計分組合**：${evaluation.cards.joinToString(" ") { getCardEmojiString(it) }}\n")
        responseText.append("手牌牌型：**${evaluation.type.displayName}** (乘數: x${evaluation.type.multiplier})\n")
        responseText.append("獲得分數：**${evaluation.score} 分** 🏆\n")

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
