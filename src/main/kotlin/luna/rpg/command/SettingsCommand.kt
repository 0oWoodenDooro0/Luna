package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.boolean
import luna.core.Command
import luna.rpg.*
import luna.rpg.repository.PlayerRepository

class SettingsCommand : Command {
    override val name = "settings"
    override val description = "調整冒險設定"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            boolean("auto_advance", "是否在探索完指定房間後自動前往下一層") {
                required = true
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val autoAdvance = interaction.command.booleans["auto_advance"] ?: true

        PlayerRepository.updateAutoAdvance(userId, autoAdvance)

        val response = interaction.deferEphemeralResponse()
        response.respond { 
            content = "設定已更新：自動進階：${if (autoAdvance) "開啟" else "關閉"}"
        }
    }
}
