package luna.preview

import dev.kord.core.Kord
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import luna.core.Command
import luna.core.JsonLogger
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext

internal fun safeScope(kord: Kord): CoroutineScope {
    val hasValidContext =
        runCatching {
            kord.coroutineContext.fold(true) { acc, _ -> acc }
        }.getOrDefault(false)

    return if (hasValidContext) kord else CoroutineScope(Dispatchers.Default)
}

/**
 * Luna plugin implementation encapsulating the Social Preview capability.
 * Manages guild preview settings database schema, registers the /preview slash command,
 * and attaches message creation listeners for social media link resolution and preview generation.
 */
class SocialPreviewPlugin(
    private val commandFactory: (PreviewSettingsStorage) -> Command = { PreviewCommand(it) },
) : LunaPlugin {
    override val name: String = "social-preview"

    var settingsStorage: PreviewSettingsStorage? = null
        private set

    var previewCommand: Command? = null
        private set

    override suspend fun initialize(context: PluginContext) {
        val storage = PreviewSettingsStorage(context.database)
        settingsStorage = storage

        val command = commandFactory(storage)
        previewCommand = command
        command.register(context.kord)

        val scope = safeScope(context.kord)
        context.kord.on<ChatInputCommandInteractionCreateEvent>(scope = scope) {
            if (interaction.command.rootName == command.name) {
                try {
                    command.handle(interaction)
                    JsonLogger.log(
                        layer = "COMMAND",
                        component = command.javaClass.simpleName,
                        operation = "handle",
                        data =
                            mapOf(
                                "userId" to interaction.user.id.toString(),
                                "command" to command.name,
                            ),
                    )
                } catch (e: Exception) {
                    JsonLogger.error(
                        layer = "COMMAND",
                        component = command.javaClass.simpleName,
                        operation = "handle",
                        data =
                            mapOf(
                                "userId" to interaction.user.id.toString(),
                                "command" to command.name,
                            ),
                        errorMessage = e.message,
                    )
                    throw e
                }
            }
        }

        SocialPreviewService.register(context.kord, storage, scope = scope)
    }
}
