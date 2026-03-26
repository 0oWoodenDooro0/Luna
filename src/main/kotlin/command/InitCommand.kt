package website.woodendoor.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import website.woodendoor.Command
import website.woodendoor.PlayerService

/**
 * Command to initialize a player's character.
 */
class InitCommand : Command {
    override val name = "init"
    override val description = "建立你的遊戲角色！"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userDiscordId = interaction.user.id.toString()
        val success = PlayerService.registerPlayer(userDiscordId)

        if (!success) {
            val response = interaction.deferEphemeralResponse()
            response.respond {
                content = "你已經建立過角色了，不需要重複執行！"
            }
        } else {
            val response = interaction.deferPublicResponse()
            response.respond {
                content = "🎉 角色建立成功！歡迎來到這個世界，輸入 /hunt 開始你的冒險吧！"
            }
        }
    }
}
