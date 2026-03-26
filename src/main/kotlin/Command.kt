package website.woodendoor

import dev.kord.core.Kord
import dev.kord.core.entity.interaction.ChatInputCommandInteraction

interface Command {
    val name: String
    val description: String

    suspend fun register(kord: Kord)
    suspend fun handle(interaction: ChatInputCommandInteraction)
}
