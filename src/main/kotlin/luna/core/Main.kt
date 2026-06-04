package luna.core

import dev.kord.common.entity.ButtonStyle
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.edit
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.event.interaction.ButtonInteractionCreateEvent
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.event.interaction.SelectMenuInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.component.actionRow
import luna.core.JsonLogger
import luna.poker.command.DrawCommand
import luna.poker.command.UpgradeCommand
import luna.undercover.UndercoverGame
import luna.undercover.UndercoverManager
import luna.undercover.command.RevealCommand
import luna.undercover.command.UndercoverCommand

suspend fun main() {
    val kord = Kord(System.getenv("DISCORD_TOKEN") ?: error("Missing discord token"))

    val commands =
        listOf(
            UndercoverCommand(),
            RevealCommand(),
            DrawCommand(),
            UpgradeCommand(),
        )
    commands.forEach { it.register(kord) }

    kord.on<ChatInputCommandInteractionCreateEvent> {
        val commandName = interaction.command.rootName
        val userId = interaction.user.id.toString()
        val options = interaction.command.options.mapValues { it.value.toString() }

        val matchedCommand = commands.find { it.name == commandName }

        if (matchedCommand != null) {
            try {
                matchedCommand.handle(interaction)
                JsonLogger.log(
                    layer = "COMMAND",
                    component = matchedCommand.javaClass.simpleName,
                    operation = "handle",
                    data =
                        mapOf(
                            "userId" to userId,
                            "command" to commandName,
                            "options" to options,
                        ),
                )
            } catch (e: Exception) {
                JsonLogger.error(
                    layer = "COMMAND",
                    component = matchedCommand.javaClass.simpleName,
                    operation = "handle",
                    data =
                        mapOf(
                            "userId" to userId,
                            "command" to commandName,
                            "options" to options,
                        ),
                    errorMessage = e.message,
                )
                throw e
            }
        }
    }

    kord.on<SelectMenuInteractionCreateEvent> {
        if (interaction.componentId == "undercover_vote") {
            val guildId = interaction.data.guildId.value ?: return@on
            val voterId = interaction.user.id
            val game = UndercoverManager.activeGames[guildId]

            if (game == null) {
                val response = interaction.deferEphemeralResponse()
                response.respond { content = "目前沒有正在進行的遊戲！" }
                return@on
            }

            if (!game.players.containsKey(voterId)) {
                val response = interaction.deferEphemeralResponse()
                response.respond { content = "你沒有參與這場遊戲，無法投票喔！" }
                return@on
            }

            val votedUserIdString = interaction.values.firstOrNull() ?: return@on
            val votedUserId = Snowflake(votedUserIdString)

            game.votes[voterId] = votedUserId
            val votedUserName = game.players[votedUserId] ?: "未知玩家"

            val response = interaction.deferEphemeralResponse()
            response.respond { content = "你已秘密投票給：$votedUserName" }
        }
    }

    kord.on<ButtonInteractionCreateEvent> {
        val customId = interaction.componentId
        if (customId.startsWith("upgrade_draw_")) {
            val targetUserId = customId.substringAfter("upgrade_draw_")
            val clickerId = interaction.user.id.toString()
            val clickerUsername = interaction.user.username

            if (clickerId != targetUserId) {
                val response = interaction.deferEphemeralResponse()
                response.respond {
                    content = "你只能升級你自己的能力！請使用 `/升級` 指令開啟你自己的選單。"
                }
                return@on
            }

            val pokerUser = luna.poker.User.getOrCreate(clickerId)
            val cost = pokerUser.getNextDrawUpgradeCost()

            if (pokerUser.drawCount >= 7 || cost == null) {
                val response = interaction.deferEphemeralResponse()
                response.respond {
                    content = "你的抽牌數量已經達到上限 (7 抽)！"
                }
                return@on
            }

            if (pokerUser.score < cost) {
                val response = interaction.deferEphemeralResponse()
                response.respond {
                    content = "升級失敗：分數不足！升級需要 $cost 分，但你目前只有 ${pokerUser.score} 分。"
                }
                return@on
            }

            val success = pokerUser.upgradeDrawCount()
            if (success) {
                val response = interaction.deferPublicMessageUpdate()
                response.edit {
                    content = UpgradeCommand.buildMenuText(clickerId, clickerUsername, pokerUser)
                    val nextCost = pokerUser.getNextDrawUpgradeCost()
                    if (pokerUser.drawCount < 7 && nextCost != null) {
                        actionRow {
                            interactionButton(ButtonStyle.Primary, "upgrade_draw_${clickerId}") {
                                label = "升級抽牌數量 (${pokerUser.drawCount} ➔ ${pokerUser.drawCount + 1} 抽)"
                            }
                        }
                    } else {
                        actionRow {
                            interactionButton(ButtonStyle.Secondary, "upgrade_draw_max") {
                                label = "抽牌數量已達上限 (7 抽)"
                                disabled = true
                            }
                        }
                    }
                }

                JsonLogger.log(
                    layer = "UPGRADE",
                    component = "UpgradeDraw",
                    operation = "upgradeDrawCount",
                    data = mapOf(
                        "userId" to clickerId,
                        "username" to clickerUsername,
                        "newDrawCount" to pokerUser.drawCount,
                        "cost" to cost,
                        "remainingScore" to pokerUser.score
                    )
                )
            } else {
                val response = interaction.deferEphemeralResponse()
                response.respond {
                    content = "升級失敗，請重試！"
                }
            }
        }
    }

    kord.login()
}
