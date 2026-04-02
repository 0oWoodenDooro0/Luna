package website.woodendoor.rpg

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.boolean
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.Command
import website.woodendoor.repository.PlayersTable

class AutoAdvanceCommand : Command {
    override val name = "auto_advance"
    override val description = "設定是否在戰鬥勝利後自動前往下一層"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            boolean("enabled", "是否開啟自動進階") {
                required = true
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val enabled = interaction.command.booleans["enabled"] ?: false

        transaction {
            PlayersTable.update({ PlayersTable.id eq userId }) {
                it[autoAdvance] = enabled
            }
        }

        val response = interaction.deferEphemeralResponse()
        response.respond { 
            content = "自動進階設定已更新為：${if (enabled) "開啟" else "關閉"}"
        }
    }
}
