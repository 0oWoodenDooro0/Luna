package luna.undercover.command

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.component.actionRow
import dev.kord.rest.builder.component.option
import dev.kord.rest.builder.interaction.string
import luna.core.Command
import luna.undercover.UndercoverGame
import luna.undercover.UndercoverManager

class UndercoverCommand : Command {
    override val name = "undercover"
    override val description = "開始一場誰是臥底遊戲！"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            string("players", "請標記所有參與的玩家 (用空白分隔，至少3人)") {
                required = true
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val guildId = interaction.data.guildId.value ?: return
        val command = interaction.command
        val playersString = command.strings["players"] ?: ""

        // 使用正規表達式找出所有被標記的玩家 ID
        val regex = Regex("<@!?(\\d+)>")
        val matches = regex.findAll(playersString).toList()

        if (matches.size < 3) {
            val response = interaction.deferEphemeralResponse()
            response.respond { content = "至少需要標記 3 名玩家才能開始遊戲！" }
            return
        }

        val playerMap = mutableMapOf<Snowflake, String>()
        val guild = interaction.kord.getGuild(guildId)

        for (match in matches) {
            val id = Snowflake(match.groupValues[1])
            val member = guild.getMemberOrNull(id)
            if (member != null) {
                playerMap[id] = member.username
            }
        }

        if (playerMap.size < 3) {
            val response = interaction.deferEphemeralResponse()
            response.respond { content = "無法找到足夠的有效玩家，請確認標記是否正確。" }
            return
        }

        val playersList = playerMap.keys.toList()
        val spy = playersList.random()

        UndercoverManager.activeGames[guildId] = UndercoverGame(
            players = playerMap,
            spy = spy,
            votes = mutableMapOf()
        )

        for (id in playersList) {
            val member = guild.getMemberOrNull(id) ?: continue
            try {
                val dm = member.getDmChannel()
                if (id == spy) {
                    dm.createMessage("你是臥底！請隱藏好你的身分。")
                } else {
                    dm.createMessage("你是普通平民！請試著找出臥底。")
                }
            } catch (_: Exception) {
            }
        }

        val response = interaction.deferPublicResponse()
        response.respond {
            content = "已經透過私訊發送身分給所有玩家！遊戲開始！\n請大家討論後，在下方選單投票選出你認為的臥底："
            actionRow {
                stringSelect("undercover_vote") {
                    placeholder = "請選擇你認為的臥底..."
                    for ((id, name) in playerMap) {
                        option(name, id.toString())
                    }
                }
            }
        }
    }
}
