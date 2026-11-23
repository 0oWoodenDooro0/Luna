package luna.chatter.memory

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.channel.MessageChannel
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import luna.chatter.ChatterConfig
import luna.chatter.ChatterNoiseFilter
import luna.core.JsonLogger
import luna.core.ai.AiTraceRecorder
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.NoOpAiTraceRecorder
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.io.Closeable

/**
 * Deep lifecycle module owning `Chatter Pipeline` `Long-Term Memory`:
 * SQLite store, embeddings, `/memory` slash command, retrieval tool handler,
 * merged `MemoryDistiller`, and all memory coroutine jobs.
 *
 * Closing this service cancels all memory jobs and closes owned resources without
 * closing the injected [HttpClient] owned by `ChatterPlugin`.
 */
class ChatterMemoryService internal constructor(
    val memoryStore: ChatterMemoryStore,
    val memoryCommand: MemoryCommand,
    val toolHandler: ChatterMemoryToolHandler?,
    val distiller: MemoryDistiller?,
    private val embeddingClient: GeminiEmbeddingClient?,
) : Closeable {
    private val logger = LoggerFactory.getLogger(ChatterMemoryService::class.java)

    private var commandJob: Job? = null
    private var observerJob: Job? = null
    private var tickerJob: Job? = null

    /**
     * Registers `/memory` command handling and, when [chatterEnabled] is true,
     * starts background `Memory Distillation` observation and periodic ticking.
     */
    suspend fun start(
        kord: Kord,
        scope: CoroutineScope,
        chatterEnabled: Boolean,
    ) {
        try {
            memoryCommand.register(kord)
            commandJob =
                kord.on<ChatInputCommandInteractionCreateEvent>(scope = scope) {
                    if (interaction.command.rootName == memoryCommand.name) {
                        try {
                            memoryCommand.handle(interaction)
                            JsonLogger.log(
                                layer = "COMMAND",
                                component = memoryCommand.javaClass.simpleName,
                                operation = "handle",
                                data =
                                    mapOf(
                                        "userId" to interaction.user.id.toString(),
                                        "command" to memoryCommand.name,
                                    ),
                            )
                        } catch (e: Exception) {
                            JsonLogger.error(
                                layer = "COMMAND",
                                component = memoryCommand.javaClass.simpleName,
                                operation = "handle",
                                data =
                                    mapOf(
                                        "userId" to interaction.user.id.toString(),
                                        "command" to memoryCommand.name,
                                    ),
                                errorMessage = e.message,
                            )
                        }
                    }
                }
        } catch (e: Exception) {
            logger.warn("Could not register /memory command to Kord: {}", e.message)
        }

        val activeDistiller = distiller
        if (!chatterEnabled || activeDistiller == null) return

        runCatching {
            observerJob = activeDistiller.registerMessageObserver(kord, scope = scope)
            tickerJob = activeDistiller.startTicker(scope = scope)
        }.onFailure { e ->
            logger.warn("Failed to initialize Memory Distillation; continuing without background distillation: {}", e.message)
        }
    }

    override fun close() {
        commandJob?.cancel()
        commandJob = null
        observerJob?.cancel()
        observerJob = null
        tickerJob?.cancel()
        tickerJob = null
        embeddingClient?.close()
    }

    companion object {
        fun create(
            database: Database,
            sharedHttpClient: HttpClient,
            chatterConfig: ChatterConfig,
            memoryConfig: ChatterMemoryConfig,
            noiseFilter: ChatterNoiseFilter,
            allowedChannels: Set<Snowflake>,
            chatterEnabled: Boolean,
            kord: Kord,
            traceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
        ): ChatterMemoryService {
            val store =
                SqliteChatterMemoryStore(
                    database = database,
                    defaultRecencyExcludeMinutes = memoryConfig.recencyExcludeMinutes.toInt(),
                    defaultScoreCutoff = memoryConfig.scoreCutoff,
                    maxPerUser = memoryConfig.maxPerUser,
                    maxPerGuild = memoryConfig.maxPerGuild,
                )
            val embeddingClient =
                if (chatterConfig.isConfigured) {
                    GeminiEmbeddingClient(
                        httpClient = sharedHttpClient,
                        apiKey = chatterConfig.apiKey,
                        baseUrl = chatterConfig.cleanBaseUrl,
                        model = memoryConfig.cleanEmbeddingModel,
                    )
                } else {
                    null
                }
            val toolHandler =
                embeddingClient?.let {
                    ChatterMemoryToolHandler(
                        embeddingClient = it,
                        memoryStore = store,
                        config = memoryConfig,
                    )
                }
            val distiller =
                if (embeddingClient != null && chatterEnabled && chatterConfig.isConfigured) {
                    MemoryDistiller(
                        memoryStore = store,
                        embeddingClient = embeddingClient,
                        interactionClient =
                            GeminiInteractionClient(
                                httpClient = sharedHttpClient,
                                traceRecorder = traceRecorder,
                            ),
                        noiseFilter = noiseFilter,
                        config = memoryConfig,
                        apiKey = chatterConfig.apiKey,
                        baseUrl = chatterConfig.cleanBaseUrl,
                        traceRecorder = traceRecorder,
                        messagesAfterFetcher = { channelId, afterMessageId, limit ->
                            runCatching { kord.getChannelOf<MessageChannel>(channelId) }
                                .getOrNull()
                                ?.getMessagesAfter(afterMessageId, limit = limit)
                                ?.toList()
                                ?: emptyList()
                        },
                        kordSelfIdProvider = { runCatching { kord.selfId }.getOrNull() },
                        allowedChannels = allowedChannels,
                        chatterEnabled = chatterEnabled,
                    )
                } else {
                    null
                }
            return ChatterMemoryService(
                memoryStore = store,
                memoryCommand = MemoryCommand(store),
                toolHandler = toolHandler,
                distiller = distiller,
                embeddingClient = embeddingClient,
            )
        }
    }
}
