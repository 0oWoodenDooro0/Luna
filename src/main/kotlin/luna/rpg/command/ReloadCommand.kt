package luna.rpg.command

import dev.kord.common.entity.Permission
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import luna.core.Command
import luna.rpg.RpgConfig

class ReloadCommand : Command {
    override val name = "reload"
    override val description = "重新讀取 RPG 配置文件 (僅限管理員)"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val response = interaction.deferEphemeralResponse()
        
        val guildId = interaction.data.guildId.value
        if (guildId == null) {
            response.respond { content = "此指令只能在伺服器中使用。" }
            return
        }

        val member = interaction.kord.getGuild(guildId).getMemberOrNull(interaction.user.id)
        val permissions = member?.getPermissions()
        
        if (permissions == null || !permissions.contains(Permission.Administrator)) {
            response.respond { 
                content = "抱歉，只有伺服器管理員才能執行此指令。"
            }
            return
        }

        RpgConfig.reload()
        response.respond { 
            content = "RPG 配置文件已重新讀取！"
        }
    }
}
