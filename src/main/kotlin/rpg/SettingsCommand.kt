package website.woodendoor.rpg

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.boolean
import dev.kord.rest.builder.interaction.integer
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.Command
import website.woodendoor.repository.PlayersTable

class SettingsCommand : Command {
    override val name = "settings"
    override val description = "調整冒險設定"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            boolean("auto_advance", "是否在探索完指定房間後自動前往下一層")
            integer("floor_size", "每層樓包含的房間數量 (建議 3-10)")
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val autoAdvance = interaction.command.booleans["auto_advance"]
        val floorSize = interaction.command.integers["floor_size"]

        if (autoAdvance == null && floorSize == null) {
            val response = interaction.deferEphemeralResponse()
            response.respond { content = "請至少指定一個設定項！" }
            return
        }

        transaction {
            PlayersTable.update({ PlayersTable.id eq userId }) {
                if (autoAdvance != null) {
                    it[this.autoAdvance] = autoAdvance
                }
                if (floorSize != null) {
                    it[this.floorSize] = floorSize.toInt()
                }
            }
        }

        val response = interaction.deferEphemeralResponse()
        response.respond { 
            val parts = mutableListOf<String>()
            if (autoAdvance != null) parts.add("自動進階：${if (autoAdvance) "開啟" else "關閉"}")
            if (floorSize != null) parts.add("每層房間數：$floorSize")
            content = "設定已更新：\n" + parts.joinToString("\n")
        }
    }
}
