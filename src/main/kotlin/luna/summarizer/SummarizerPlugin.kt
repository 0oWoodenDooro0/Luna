package luna.summarizer

import luna.core.ai.SocialPeekFactory
import luna.plugin.LunaPlugin
import luna.plugin.PluginContext
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
        val apiKey = context.env.string("GEMINI_API_KEY", "")

        val model =
            context.env
                .string("GEMINI_MODEL", GeminiConfig.DEFAULT_MODEL)
                .removePrefix("models/")

        val baseUrl =
            context.env
                .string("GEMINI_BASE_URL", GeminiConfig.DEFAULT_BASE_URL)
                .trimEnd('/')
                .removeSuffix("/v1beta")

        val emoji = context.env.string("SUMMARY_EMOJI", SummaryService.DEFAULT_EMOJI)

        val youtubeApiKey = context.env.string("YOUTUBE_API_KEY")

        val historyLimit =
            context.env.int(
                "SUMMARY_MAX_HISTORY_MESSAGES",
                ConversationSliceCollector.DEFAULT_MAX_HISTORY_MESSAGES,
                1..100,
            )

        val gap =
            context.env
                .long(
                    "SUMMARY_INACTIVITY_GAP_MINUTES",
                    ConversationSliceCollector.DEFAULT_INACTIVITY_GAP.inWholeMinutes,
                    1L..Long.MAX_VALUE,
                ).minutes

        val characterBudget =
            context.env.int(
                "SUMMARY_MAX_CHARACTER_BUDGET",
                ConversationSliceCollector.DEFAULT_MAX_CHARACTER_BUDGET,
                1..Int.MAX_VALUE,
            )

        val lookbackLimit =
            context.env.int(
                "SUMMARY_MAX_LOOKBACK_MESSAGES",
                ConversationSliceCollector.DEFAULT_MAX_LOOKBACK_MESSAGES,
                1..ConversationSliceCollector.DEFAULT_MAX_LOOKBACK_MESSAGES,
            )

        val imagesLimit =
            context.env.int(
                "SUMMARY_MAX_IMAGES",
                ConversationSliceCollector.DEFAULT_MAX_IMAGES,
                1..Int.MAX_VALUE,
            )

        this.configuredEmoji = emoji
        this.maxHistoryMessages = historyLimit
        this.inactivityGap = gap
        this.maxCharacterBudget = characterBudget
        this.maxLookbackMessages = lookbackLimit
        this.maxImages = imagesLimit

        val searchGrounding =
            context.env.boolean(
                "GEMINI_SEARCH_GROUNDING_ENABLED",
                GeminiConfig.DEFAULT_SEARCH_GROUNDING_ENABLED,
            )

        val urlContext =
            context.env.boolean(
                "GEMINI_URL_CONTEXT_ENABLED",
                GeminiConfig.DEFAULT_URL_CONTEXT_ENABLED,
            )

        val geminiConfig =
            GeminiConfig(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl,
                searchGroundingEnabled = searchGrounding,
                urlContextEnabled = urlContext,
            )

        val gemini =
            summarizerFactory?.invoke(geminiConfig)
                ?: GeminiSummarizer(
                    config = geminiConfig,
                    traceRecorder = context.aiTraceRecorder,
                )
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
                traceRecorder = context.aiTraceRecorder,
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
