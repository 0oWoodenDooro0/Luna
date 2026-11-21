package luna.chatter.emoji

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import luna.chatter.ChatterConfig
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTracePipeline
import luna.core.ai.AiTraceTrigger
import luna.core.ai.ChatImageAttachment
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.GeminiInteractionGenerationConfig
import luna.core.ai.GeminiInteractionRequest
import luna.core.ai.InteractionExecutionResult
import luna.core.ai.InteractionTraceContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Target descriptor for resolving a Discord custom emoji's semantic meaning via Gemini Vision.
 */
internal data class EmojiVisionRequest(
    val id: String,
    val name: String,
    val isAnimated: Boolean,
)

/**
 * Tracks a single conversation turn's shared vision-resolution budget across pipeline stages
 * (`Participation Decision`, reply generation, and `Silent Reaction Path`) so a turn never
 * chains sequential round timeouts or retries already-attempted emojis.
 */
internal class EmojiVisionTurnBudget {
    private val attemptedEmojiIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val consumedVisionNanos: AtomicLong = AtomicLong(0L)

    fun remainingTimeoutMs(roundTimeoutMs: Long): Long {
        if (roundTimeoutMs <= 0L) return 0L
        val elapsedMs = (consumedVisionNanos.get() / 1_000_000L).coerceAtLeast(0L)
        return (roundTimeoutMs - elapsedMs).coerceAtLeast(0L)
    }

    fun recordVisionElapsedNanos(elapsedNanos: Long) {
        if (elapsedNanos > 0L) {
            consumedVisionNanos.addAndGet(elapsedNanos)
        }
    }

    fun filterUnattempted(targets: List<EmojiVisionRequest>): List<EmojiVisionRequest> = targets.filter { it.id !in attemptedEmojiIds }

    fun markAttempted(targets: List<EmojiVisionRequest>) {
        targets.forEach { attemptedEmojiIds.add(it.id) }
    }
}

/**
 * Opt-in, bounded Gemini Vision resolver for `Custom Emoji Meaning` via `CDN Direct Ingestion`.
 */
internal class ChatterEmojiVisionResolver(
    private val store: ChatterEmojiSqliteStore,
    visionModel: String?,
    private val interactionClient: GeminiInteractionClient,
    private val baseUrl: String = ChatterConfig.DEFAULT_BASE_URL,
    private val apiKey: String = "",
    private val clock: Clock = Clock.systemUTC(),
    maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    val roundTimeoutMs: Long = DEFAULT_ROUND_TIMEOUT_MS,
    private val failureCooldownMs: Long = DEFAULT_FAILURE_COOLDOWN_MS,
) {
    private val logger = LoggerFactory.getLogger(ChatterEmojiVisionResolver::class.java)
    private val cleanVisionModel: String? =
        visionModel?.trim()?.removePrefix("models/")?.ifBlank { null }
    private val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    private val inFlight = ConcurrentHashMap<String, Deferred<Unit>>()

    val isEnabled: Boolean
        get() = cleanVisionModel != null && apiKey.isNotBlank()

    suspend fun resolveMissing(
        targets: List<EmojiVisionRequest>,
        timeoutMs: Long? = roundTimeoutMs,
    ) {
        if (targets.isEmpty()) return
        val uniqueTargets = targets.associateBy { it.id }.values.toList()

        val existingById =
            runCatching {
                store.findMeaningsByIds(uniqueTargets.map { it.id })
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn(
                    "Failed to query Custom Emoji Meaning cache before vision resolution; degrading gracefully: {}",
                    e.message,
                )
            }.getOrNull() ?: return

        val metadataToSync = mutableListOf<EmojiVisionRequest>()
        val nowMs = clock.millis()
        val pending = mutableListOf<EmojiVisionRequest>()

        for (target in uniqueTargets) {
            val existing = existingById[target.id]
            if (existing != null) {
                if (existing.name != target.name || existing.isAnimated != target.isAnimated) {
                    metadataToSync += target
                }
                val hasMeaning = !existing.meaning.isNullOrBlank()
                val inCooldown = existing.failedUntil != null && existing.failedUntil > nowMs
                if (existing.isManualOverride || hasMeaning || inCooldown) {
                    continue
                }
            }
            pending += target
        }

        if (metadataToSync.isNotEmpty()) {
            runCatching {
                store.syncMetadataIfChanged(metadataToSync)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                logger.warn("Failed to sync metadata for {} cached emoji(s): {}", metadataToSync.size, e.message)
            }
        }

        if (!isEnabled || pending.isEmpty()) return

        if (timeoutMs != null && timeoutMs > 0L) {
            val completed =
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(timeoutMs) {
                        coroutineScope {
                            pending.map { target -> async { resolveWithDedup(target) } }.awaitAll()
                        }
                    }
                }
            if (completed == null) {
                logger.warn(
                    "On-demand Custom Emoji Meaning resolution timed out after {}ms for {} emoji(s); degrading gracefully to raw emoji tokens",
                    timeoutMs,
                    pending.size,
                )
            }
        } else {
            coroutineScope {
                pending.map { target -> async { resolveWithDedup(target) } }.awaitAll()
            }
        }
    }

    private suspend fun resolveWithDedup(target: EmojiVisionRequest) {
        while (true) {
            val existing = inFlight[target.id]
            if (existing != null) {
                try {
                    existing.await()
                    return
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }

            val created = CompletableDeferred<Unit>()
            val raced = inFlight.putIfAbsent(target.id, created)
            if (raced != null) {
                continue
            }

            try {
                resolveSingle(target)
                created.complete(Unit)
                return
            } catch (e: CancellationException) {
                created.cancel(e)
                throw e
            } catch (e: Throwable) {
                created.completeExceptionally(e)
                logger.warn(
                    "Unexpected error resolving Custom Emoji Meaning for {} ({}): {}",
                    target.id,
                    target.name,
                    e.message,
                )
                return
            } finally {
                inFlight.remove(target.id, created)
            }
        }
    }

    private suspend fun resolveSingle(target: EmojiVisionRequest) {
        val model = cleanVisionModel ?: return
        semaphore.withPermit {
            currentCoroutineContext().ensureActive()

            val currentRecord =
                runCatching {
                    store.findMeaningById(target.id)
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    logger.warn(
                        "Failed to re-check Custom Emoji Meaning cache for {} ({}) after acquiring semaphore; continuing with vision lookup: {}",
                        target.id,
                        target.name,
                        e.message,
                    )
                }.getOrNull()
            val nowMs = clock.millis()
            if (currentRecord != null) {
                val hasMeaning = !currentRecord.meaning.isNullOrBlank()
                val inCooldown = currentRecord.failedUntil != null && currentRecord.failedUntil > nowMs
                if (currentRecord.isManualOverride || hasMeaning || inCooldown) {
                    return@withPermit
                }
            }

            val result =
                if (target.isAnimated) {
                    val gifAttempt =
                        requestVisionMeaning(
                            model = model,
                            target = target,
                            cdnUrl = buildCdnUrl(target.id, animated = true),
                            mimeType = "image/gif",
                        )
                    when (gifAttempt) {
                        is VisionAttemptResult.Success -> {
                            gifAttempt
                        }

                        is VisionAttemptResult.Failure -> {
                            logger.warn(
                                "Animated custom emoji {} ({}) vision resolution failed for .gif ({}); retrying with static .png fallback",
                                target.id,
                                target.name,
                                gifAttempt.reason,
                            )
                            requestVisionMeaning(
                                model = model,
                                target = target,
                                cdnUrl = buildCdnUrl(target.id, animated = false),
                                mimeType = "image/png",
                            )
                        }
                    }
                } else {
                    requestVisionMeaning(
                        model = model,
                        target = target,
                        cdnUrl = buildCdnUrl(target.id, animated = false),
                        mimeType = "image/png",
                    )
                }

            when (result) {
                is VisionAttemptResult.Success -> {
                    store.upsertResolvedMeaning(
                        emojiId = target.id,
                        name = target.name,
                        isAnimated = target.isAnimated,
                        meaning = result.meaning,
                    )
                }

                is VisionAttemptResult.Failure -> {
                    val failedUntil = clock.millis() + failureCooldownMs
                    logger.warn(
                        "Failed to resolve Custom Emoji Meaning for emoji {} ({}): {}; setting 1-hour failure cooldown until {}",
                        target.id,
                        target.name,
                        result.reason,
                        failedUntil,
                    )
                    store.recordFailureCooldown(
                        emojiId = target.id,
                        name = target.name,
                        isAnimated = target.isAnimated,
                        failedUntilEpochMs = failedUntil,
                    )
                }
            }
        }
    }

    private suspend fun requestVisionMeaning(
        model: String,
        target: EmojiVisionRequest,
        cdnUrl: String,
        mimeType: String,
    ): VisionAttemptResult {
        val promptText = buildVisionPrompt(target.name)
        val request =
            GeminiInteractionRequest(
                model = model,
                systemInstruction = VISION_SYSTEM_INSTRUCTION,
                input =
                    GeminiInteractionClient.buildMultimodalInputs(
                        promptText = promptText,
                        imageAttachments = listOf(ChatImageAttachment(url = cdnUrl, mimeType = mimeType)),
                    ),
                generationConfig = GeminiInteractionGenerationConfig(maxOutputTokens = MAX_OUTPUT_TOKENS),
                background = false,
            )
        val traceContext =
            InteractionTraceContext(
                inputUrls = listOf(cdnUrl),
                hasImages = true,
                imageCount = 1,
                stage = AiAttemptStage.CHATTER_GENERATE,
                pipeline = AiTracePipeline.CHATTER,
                trigger = AiTraceTrigger.AMBIENT,
                stripOutputEmojis = true,
            )

        return when (val response = interactionClient.interact(baseUrl, apiKey, request, traceContext)) {
            is InteractionExecutionResult.Success -> {
                val cleaned = sanitizeMeaning(response.text)
                if (cleaned != null) {
                    VisionAttemptResult.Success(cleaned)
                } else {
                    VisionAttemptResult.Failure("empty or unusable vision output")
                }
            }

            is InteractionExecutionResult.Error -> {
                VisionAttemptResult.Failure(response.message)
            }

            is InteractionExecutionResult.FunctionCall -> {
                VisionAttemptResult.Failure("unexpected function call response")
            }
        }
    }

    private sealed interface VisionAttemptResult {
        data class Success(
            val meaning: String,
        ) : VisionAttemptResult

        data class Failure(
            val reason: String,
        ) : VisionAttemptResult
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENCY = 5
        const val DEFAULT_ROUND_TIMEOUT_MS = 5_000L
        const val DEFAULT_FAILURE_COOLDOWN_MS = 3_600_000L
        private const val MAX_OUTPUT_TOKENS = 64

        private const val VISION_SYSTEM_INSTRUCTION =
            "You analyze Discord custom emojis for a chat bot. " +
                "Rely primarily on the visual appearance of the emoji image. " +
                "The provided emoji name is only an auxiliary hint—ignore opaque or meaningless names (such as a1, blob3, img01). " +
                "Output ONLY a single concise Traditional Chinese semantic or emotional meaning (about 5 to 15 characters), " +
                "with no quotes, markdown, or extra explanation."

        private val jsonParser =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
                explicitNulls = false
            }

        fun buildCdnUrl(
            emojiId: String,
            animated: Boolean,
        ): String {
            val ext = if (animated) "gif" else "png"
            return "https://cdn.discordapp.com/emojis/$emojiId.$ext"
        }

        fun buildVisionPrompt(emojiName: String): String = "請說明這個 Discord 自訂表情符號表達的語意或情緒（輔助名稱提示：:$emojiName:）。請只輸出簡短繁體中文意思。"

        fun sanitizeMeaning(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val firstLine =
                raw
                    .lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: return null
            val stripped =
                firstLine
                    .trim('"', '\'', '`', '「', '」', '『', '』', '(', ')', '（', '）')
                    .trim()
            if (stripped.isEmpty() || stripped.none { it.isLetterOrDigit() }) {
                return null
            }
            return stripped
        }

        fun createDefaultHttpClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(jsonParser)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 10_000
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 10_000
                }
            }
    }
}
