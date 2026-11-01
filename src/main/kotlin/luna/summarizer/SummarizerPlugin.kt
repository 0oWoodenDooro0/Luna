package luna.summarizer

import dev.socialpeek.SocialPeek
import dev.socialpeek.model.PeekPost
import dev.socialpeek.resolver.bilibili.BilibiliResolver
import dev.socialpeek.resolver.facebook.FacebookResolver
import dev.socialpeek.resolver.generic.GenericWebResolver
import dev.socialpeek.resolver.instagram.InstagramResolver
import dev.socialpeek.resolver.reddit.RedditResolver
import dev.socialpeek.resolver.threads.ThreadsResolver
import dev.socialpeek.resolver.x.XResolver
import dev.socialpeek.resolver.youtube.YouTubeResolver
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
            configuredEmoji: String,
            maxHistoryMessages: Int,
            inactivityGap: Duration,
            maxCharacterBudget: Int,
            peekResolver: suspend (String) -> PeekPost?,
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

    var maxHistoryMessages: Int = SummaryService.DEFAULT_MAX_HISTORY_MESSAGES
        internal set

    var inactivityGap: Duration = SummaryService.DEFAULT_INACTIVITY_GAP
        internal set

    var maxCharacterBudget: Int = SummaryService.DEFAULT_MAX_CHARACTER_BUDGET
        internal set

    override suspend fun initialize(context: PluginContext) {
        val apiKey =
            context.config<String>("GEMINI_API_KEY")
                ?: context.config<String>("gemini.apiKey")
                ?: context.config<String>("gemini.api_key")
                ?: ""

        val model =
            context.config<String>("GEMINI_MODEL")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("gemini.model")?.takeIf { it.isNotBlank() }
                ?: GeminiConfig.DEFAULT_MODEL

        val baseUrl =
            context.config<String>("GEMINI_BASE_URL")?.takeIf { it.isNotBlank() }
                ?: context.config<String>("gemini.baseUrl")?.takeIf { it.isNotBlank() }
                ?: GeminiConfig.DEFAULT_BASE_URL

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
                ?: SummaryService.resolveMaxHistoryMessages()

        val inactivityGapMinutes =
            context.config<Long>("SUMMARY_INACTIVITY_GAP_MINUTES")
                ?: context.config<Int>("SUMMARY_INACTIVITY_GAP_MINUTES")?.toLong()
                ?: context.config<Long>("summary.inactivityGapMinutes")
                ?: context.config<Int>("summary.inactivityGapMinutes")?.toLong()

        val gap =
            if (inactivityGapMinutes != null && inactivityGapMinutes > 0) {
                inactivityGapMinutes.minutes
            } else {
                SummaryService.resolveInactivityGap()
            }

        val characterBudget =
            context.config<Int>("SUMMARY_MAX_CHARACTER_BUDGET")?.takeIf { it > 0 }
                ?: context.config<Int>("summary.maxCharacterBudget")?.takeIf { it > 0 }
                ?: SummaryService.resolveMaxCharacterBudget()

        this.configuredEmoji = emoji
        this.maxHistoryMessages = historyLimit
        this.inactivityGap = gap
        this.maxCharacterBudget = characterBudget

        val geminiConfig =
            GeminiConfig(
                apiKey = apiKey,
                model = model,
                baseUrl = baseUrl,
            )

        val gemini =
            summarizerFactory?.invoke(geminiConfig)
                ?: GeminiSummarizer(config = geminiConfig)
        this.summarizer = gemini

        val peekResolver: suspend (String) -> PeekPost? =
            if (!youtubeApiKey.isNullOrBlank()) {
                val peekClient =
                    SocialPeek
                        .builder()
                        .includeDefaultResolvers(false)
                        .addResolver(FacebookResolver())
                        .addResolver(BilibiliResolver())
                        .addResolver(XResolver())
                        .addResolver(InstagramResolver())
                        .addResolver(ThreadsResolver())
                        .addResolver(YouTubeResolver(apiKey = youtubeApiKey))
                        .addResolver(RedditResolver())
                        .addResolver(GenericWebResolver())
                        .build()
                val resolver: suspend (String) -> PeekPost? = { url -> peekClient.peekOrNull(url) }
                resolver
            } else {
                { url -> SocialPeek.peekOrNull(url) }
            }

        val service =
            summaryServiceFactory?.invoke(
                gemini,
                emoji,
                historyLimit,
                gap,
                characterBudget,
                peekResolver,
            ) ?: SummaryService(
                summarizer = gemini,
                peekResolver = peekResolver,
                configuredEmoji = emoji,
                maxHistoryMessages = historyLimit,
                inactivityGap = gap,
                maxCharacterBudget = characterBudget,
                kord = context.kord,
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
