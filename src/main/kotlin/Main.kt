package website.woodendoor

import dev.kord.core.Kord
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import website.woodendoor.command.HuntCommand
import website.woodendoor.command.InitCommand

suspend fun main() {
    initDatabase()
    val kord = Kord(System.getenv("DISCORD_TOKEN") ?: error("Missing discord token"))

    val commands = listOf(
        InitCommand(),
        HuntCommand()
    )
    commands.forEach { it.register(kord) }

    kord.on<ChatInputCommandInteractionCreateEvent> {
        val commandName = interaction.command.rootName

        val matchedCommand = commands.find { it.name == commandName }

        matchedCommand?.handle(interaction)
    }

    kord.login()
}