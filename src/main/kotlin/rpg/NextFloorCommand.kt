package website.woodendoor.rpg

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import website.woodendoor.Command
import website.woodendoor.repository.PlayersTable

class NextFloorCommand : Command {
    override val name = "next_floor"
    override val description = "前往下一層樓"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()

        val newFloor = transaction {
            val current = PlayersTable.selectAll().where { PlayersTable.id eq userId }.singleOrNull()
            if (current == null) {
                null
            } else {
                val next = current[PlayersTable.currentFloor] + 1
                PlayersTable.update({ PlayersTable.id eq userId }) {
                    it[currentFloor] = next
                }
                next
            }
        }

        val response = interaction.deferPublicResponse()
        if (newFloor != null) {
            response.respond { content = "你已經成功前往第 $newFloor 層！" }
        } else {
            response.respond { content = "找不到你的角色資料，請先使用 /status 或 /explore。" }
        }
    }
}
