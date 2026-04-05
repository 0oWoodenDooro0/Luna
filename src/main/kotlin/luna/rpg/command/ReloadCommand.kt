package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import luna.core.Command
import luna.rpg.RpgConfig

class ReloadCommand : Command {
    override val name = "reload"
    override val description = "重新讀取 RPG 配置文件"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        RpgConfig.reload()
        val response = interaction.deferEphemeralResponse()
        response.respond { 
            content = "RPG 配置文件已重新讀取！"
        }
    }
}
