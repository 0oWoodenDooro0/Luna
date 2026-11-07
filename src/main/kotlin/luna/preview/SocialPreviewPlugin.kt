package luna.preview

import dev.kord.core.Kord
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import dev.socialpeek.SocialPeek
import dev.socialpeek.SocialPeekClient
import dev.socialpeek.resolver.bilibili.BilibiliResolver
import dev.socialpeek.resolver.facebook.FacebookResolver
import dev.socialpeek.resolver.generic.GenericWebResolver
import dev.socialpeek.resolver.instagram.InstagramResolver
import dev.socialpeek.resolver.reddit.RedditResolver
import dev.socialpeek.resolver.threads.ThreadsResolver
import dev.socialpeek.resolver.x.XResolver
import dev.socialpeek.resolver.youtube.YouTubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import luna.core.Command
import luna.core.JsonLogger
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
import luna.plugin.config

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
    private val clientFactory: ((String?) -> SocialPeekClient)? = null,
) : LunaPlugin {
    override val name: String = "social-preview"

    var settingsStorage: PreviewSettingsStorage? = null
        private set

    var previewCommand: Command? = null
        private set

    var youtubeApiKey: String? = null
        private set

    var peekClient: SocialPeekClient? = null
        private set

    override suspend fun initialize(context: PluginContext) {
        val storage = PreviewSettingsStorage(context.database)
        settingsStorage = storage

        val apiKey =
            context.config<String>("YOUTUBE_API_KEY")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("youtube.apiKey")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("youtube.api_key")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("social_preview.youtubeApiKey")?.takeIf { it.isNotBlank() }
        this.youtubeApiKey = apiKey

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

        val client =
            clientFactory?.invoke(apiKey) ?: if (!apiKey.isNullOrBlank()) {
                SocialPeekClient(resolvers = SocialPeekClient.defaultResolvers(youtubeApiKey = apiKey))
            } else {
                JsonLogger.log(
                    layer = "PLUGIN",
                    component = "SocialPreviewPlugin",
                    operation = "initialize",
                    data = mapOf("youtubeApiKey" to "not_configured", "mode" to "degraded_oembed"),
                    status = "DEGRADED",
                )
                SocialPeek.defaultClient
            }
        this.peekClient = client

        SocialPreviewService.register(context.kord, storage, scope = scope, peekClient = client)
    }
}
