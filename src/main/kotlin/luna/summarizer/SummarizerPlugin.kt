package luna.summarizer

import luna.core.ai.SocialPeekFactory
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
import luna.plugin.config
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Luna Plugin implementation encapsulating the AI Summarization pipeline,
 * reaction event handling, and configuration resolution.
 */
class SummarizerPlugin(
    private val summarizerFactory: ((GeminiConfig) -> GeminiSummarizer)? = null,
    private val summaryServiceFactory: (
        (
            summarizer: GeminiSummarizer,
            collector: ConversationSliceCollector,
            configuredEmoji: String,
        ) -> SummaryService
    )? = null,
) : LunaPlugin {
    constructor() : this(null, null)

    override val name: String = "summarizer"

    var summarizer: GeminiSummarizer? = null
        internal set

    var summaryService: SummaryService? = null
        internal set

    var configuredEmoji: String = SummaryService.DEFAULT_EMOJI
        internal set

    var maxHistoryMessages: Int = ConversationSliceCollector.DEFAULT_MAX_HISTORY_MESSAGES
        internal set

    var inactivityGap: Duration = ConversationSliceCollector.DEFAULT_INACTIVITY_GAP
        internal set

    var maxCharacterBudget: Int = ConversationSliceCollector.DEFAULT_MAX_CHARACTER_BUDGET
        internal set

    var maxLookbackMessages: Int = ConversationSliceCollector.DEFAULT_MAX_LOOKBACK_MESSAGES
        internal set

    var maxImages: Int = ConversationSliceCollector.DEFAULT_MAX_IMAGES
        internal set

    override suspend fun initialize(context: PluginContext) {
        val apiKey =
            context.config<String>("GEMINI_API_KEY")
                ?: context.config<String>("gemini.apiKey")
                ?: context.config<String>("gemini.api_key")
                ?: ""

        val rawModel =
            context.config<String>("GEMINI_MODEL")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("gemini.model")?.takeIf { it.isNotBlank() }
                ?: GeminiConfig.DEFAULT_MODEL
        val model = rawModel.trim().removePrefix("models/")

        val rawBaseUrl =
            context.config<String>("GEMINI_BASE_URL")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("gemini.baseUrl")?.takeIf { it.isNotBlank() }
                ?: GeminiConfig.DEFAULT_BASE_URL
        val baseUrl = rawBaseUrl.trim().trimEnd('/').removeSuffix("/v1beta")

        val emoji =
            context.config<String>("SUMMARY_EMOJI")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("summary.emoji")?.takeIf { it.isNotBlank() }
                ?: SummaryService.DEFAULT_EMOJI

        val youtubeApiKey =
            context.config<String>("YOUTUBE_API_KEY")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("youtube.apiKey")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("youtube.api_key")?.takeIf { it.isNotBlank() }

        val historyLimit =
            context.config<Int>("SUMMARY_MAX_HISTORY_MESSAGES")?.coerceIn(1, 100)
                ?: context.config<Int>("summary.maxHistoryMessages")?.coerceIn(1, 100)
                ?: ConversationSliceCollector.resolveMaxHistoryMessages()

        val inactivityGapMinutes =
            context.config<Long>("SUMMARY_INACTIVITY_GAP_MINUTES")
                ?: context.config<Int>("SUMMARY_INACTIVITY_GAP_MINUTES")?.toLong()
                ?: context.config<Long>("summary.inactivityGapMinutes")
                ?: context.config<Int>("summary.inactivityGapMinutes")?.toLong()

        val gap =
            if (inactivityGapMinutes != null && inactivityGapMinutes > 0) {
                inactivityGapMinutes.minutes
            } else {
                ConversationSliceCollector.resolveInactivityGap()
            }

        val characterBudget =
            context.config<Int>("SUMMARY_MAX_CHARACTER_BUDGET")?.takeIf { it > 0 }
                ?: context.config<Int>("summary.maxCharacterBudget")?.takeIf { it > 0 }
                ?: ConversationSliceCollector.resolveMaxCharacterBudget()

        val lookbackLimit =
            context.config<Int>("SUMMARY_MAX_LOOKBACK_MESSAGES")?.coerceIn(1, ConversationSliceCollector.DEFAULT_MAX_LOOKBACK_MESSAGES)
                ?: context.config<Int>("summary.maxLookbackMessages")?.coerceIn(1, ConversationSliceCollector.DEFAULT_MAX_LOOKBACK_MESSAGES)
                ?: ConversationSliceCollector.resolveMaxLookbackMessages()

        val imagesLimit =
            context.config<Int>("SUMMARY_MAX_IMAGES")?.takeIf { it > 0 }
                ?: context.config<Int>("summary.maxImages")?.takeIf { it > 0 }
                ?: ConversationSliceCollector.resolveMaxImages()

        this.configuredEmoji = emoji
        this.maxHistoryMessages = historyLimit
        this.inactivityGap = gap
        this.maxCharacterBudget = characterBudget
        this.maxLookbackMessages = lookbackLimit
        this.maxImages = imagesLimit

        val searchGrounding =
            context.config<Boolean>("GEMINI_SEARCH_GROUNDING_ENABLED")
                ?: context.config<String>("GEMINI_SEARCH_GROUNDING_ENABLED")?.toBooleanStrictOrNull()
                ?: context.config<Boolean>("gemini.searchGroundingEnabled")
                ?: context.config<String>("gemini.searchGroundingEnabled")?.toBooleanStrictOrNull()
                ?: GeminiConfig.DEFAULT_SEARCH_GROUNDING_ENABLED

        val geminiConfig =
            GeminiConfig(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl,
                searchGroundingEnabled = searchGrounding,
            )

        val gemini =
            summarizerFactory?.invoke(geminiConfig)
                ?: GeminiSummarizer(config = geminiConfig)
        this.summarizer = gemini

        val peekResolver = SocialPeekFactory.buildPeekResolver(youtubeApiKey)

        val collector =
            ConversationSliceCollector(
                maxHistoryMessages = historyLimit,
                inactivityGap = gap,
                maxCharacterBudget = characterBudget,
                maxImages = imagesLimit,
                maxLookbackMessages = lookbackLimit,
                noiseFilter = DialogueNoiseFilter(kord = context.kord),
                peekResolver = peekResolver,
            )

        val service =
            summaryServiceFactory?.invoke(
                gemini,
                collector,
                emoji,
            ) ?: SummaryService(
                summarizer = gemini,
                collector = collector,
                configuredEmoji = emoji,
            )
        this.summaryService = service

        try {
            service.register(context.kord)
        } catch (e: Exception) {
            logger.warn("Could not register reaction listener to Kord: {}", e.message)
        }
    }

    override suspend fun shutdown() {
        summarizer?.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SummarizerPlugin::class.java)
    }
}
