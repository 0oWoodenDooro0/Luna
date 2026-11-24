package luna.chatter.memory

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import luna.core.ai.GeminiEmbeddingClient
import luna.core.ai.GeminiInteractionTool
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Handles `search_long_term_memory` function calls from `ChatterGenerator` (`/v1beta/interactions`).
 *
 * Enforces argument validation (Snowflake `target_user_id`, clamped `limit`, valid `scope`),
 * score-cutoff / recency-exclusion filtering via [ChatterMemoryStore], and 3-second Fail-Open
 * timeout/error degradation.
 */
open class ChatterMemoryToolHandler(
    private val embeddingClient: GeminiEmbeddingClient,
    private val memoryStore: ChatterMemoryStore,
    val config: ChatterMemoryConfig = ChatterMemoryConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(ChatterMemoryToolHandler::class.java)

    open suspend fun handleToolCall(
        arguments: JsonObject,
        guildId: String?,
        channelId: String?,
        defaultAuthorId: String?,
        workingMemoryStartMs: Long? = null,
    ): String {
        val cleanGuildId = guildId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanGuildId == null) {
            logger.warn("search_long_term_memory invoked without guildId; returning unavailable")
            return unavailableResponse()
        }

        val query = extractString(arguments, "query")
        if (query.isNullOrBlank()) {
            return invalidArgumentResponse("query must not be blank")
        }

        val limit = extractLimit(arguments)
        val rawScope = extractString(arguments, "scope")?.lowercase() ?: "both"
        val scope =
            when (rawScope) {
                "user" -> SearchScope.USER
                "guild" -> SearchScope.GUILD
                "both" -> SearchScope.BOTH
                else -> return invalidArgumentResponse("scope must be one of: user, guild, both")
            }

        val rawTargetUserId = extractString(arguments, "target_user_id")
        if (rawTargetUserId != null && !isValidSnowflake(rawTargetUserId)) {
            return invalidArgumentResponse("target_user_id must be a valid Discord Snowflake ID")
        }

        val resolvedTargetUserId =
            when (scope) {
                SearchScope.GUILD -> {
                    null
                }

                SearchScope.USER, SearchScope.BOTH -> {
                    rawTargetUserId ?: defaultAuthorId?.trim()?.takeIf { isValidSnowflake(it) }
                }
            }

        if (scope == SearchScope.USER && resolvedTargetUserId == null) {
            return invalidArgumentResponse("target_user_id is required when scope is 'user'")
        }

        return try {
            withTimeout(config.toolTimeoutMs) {
                val queryEmbedding = embeddingClient.embedQuery(query)
                val effectiveMaxUpdatedAtMs =
                    if (config.recencyExcludeMinutes > 0L) {
                        workingMemoryStartMs
                    } else {
                        null
                    }
                val hits =
                    memoryStore.search(
                        guildId = cleanGuildId,
                        queryText = query,
                        queryEmbedding = queryEmbedding,
                        scope = scope,
                        targetUserId = resolvedTargetUserId,
                        limit = limit,
                        recencyExcludeMinutes = config.recencyExcludeMinutes.toInt(),
                        maxUpdatedAtMs = effectiveMaxUpdatedAtMs,
                        scoreCutoff = config.scoreCutoff,
                        updateLastAccessed = true,
                    )
                val userUnderstandings =
                    if (rawTargetUserId != null && resolvedTargetUserId != null) {
                        fetchAndTouchUserUnderstandingsFailOpen(
                            guildId = cleanGuildId,
                            targetUserId = resolvedTargetUserId,
                        )
                    } else {
                        emptyList()
                    }
                formatHitsResponse(hits, userUnderstandings)
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(
                "search_long_term_memory timed out after {}ms (guildId={}, channelId={}): {}",
                config.toolTimeoutMs,
                cleanGuildId,
                channelId,
                e.message,
            )
            unavailableResponse()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "search_long_term_memory failed open (guildId={}, channelId={}): {}",
                cleanGuildId,
                channelId,
                e.message,
                e,
            )
            unavailableResponse()
        }
    }

    private fun fetchAndTouchUserUnderstandingsFailOpen(
        guildId: String,
        targetUserId: String,
    ): List<SocialUnderstandingRecord> =
        try {
            val understandings =
                memoryStore.listUserUnderstandings(
                    guildId = guildId,
                    userId = targetUserId,
                    limit = MAX_USER_UNDERSTANDINGS_IN_TOOL,
                )
            if (understandings.isNotEmpty()) {
                memoryStore.touchUnderstandings(guildId, understandings.map { it.id })
            }
            understandings
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Social Understanding lookup failed open in search_long_term_memory (guildId={}, targetUserId={}): {}",
                guildId,
                targetUserId,
                e.message,
            )
            emptyList()
        }

    private fun formatHitsResponse(
        hits: List<ChatterMemorySearchHit>,
        userUnderstandings: List<SocialUnderstandingRecord> = emptyList(),
    ): String =
        buildJsonObject {
            put("status", "ok")
            put("count", hits.size)
            put(
                "memories",
                buildJsonArray {
                    for (hit in hits) {
                        add(
                            buildJsonObject {
                                put("id", hit.memory.id)
                                put("scope", hit.memory.scope.dbValue)
                                hit.memory.targetUserId?.let { put("target_user_id", it) }
                                put("content", hit.memory.content)
                                put("score", hit.score)
                                put("updated_at", formatTemporalDate(hit.memory.updatedAt, clampFutureToToday = true))
                                hit.memory.expiresAt?.let { put("expires_at", formatTemporalDate(it)) }
                            },
                        )
                    }
                },
            )
            if (userUnderstandings.isNotEmpty()) {
                put(
                    "user_understandings",
                    buildJsonArray {
                        for (understanding in userUnderstandings) {
                            add(
                                buildJsonObject {
                                    put("id", understanding.id)
                                    put("subject", understanding.subject)
                                    put("content", understanding.content)
                                    put("updated_at", formatTemporalDate(understanding.updatedAt, clampFutureToToday = true))
                                },
                            )
                        }
                    },
                )
            }
            if (hits.isEmpty()) {
                put("note", EMPTY_RESULTS_NOTE)
            }
        }.toString()

    private fun formatTemporalDate(
        epochMs: Long,
        clampFutureToToday: Boolean = false,
    ): String {
        val zone = clock.zone
        val targetDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = clock.instant().atZone(zone).toLocalDate()
        val rawDeltaDays = ChronoUnit.DAYS.between(today, targetDate)
        val deltaDays = if (clampFutureToToday) rawDeltaDays.coerceAtMost(0L) else rawDeltaDays
        val relative =
            when {
                deltaDays == 0L -> "today"
                deltaDays > 0L -> "in ${deltaDays}d"
                else -> "${-deltaDays}d ago"
            }
        return "${DATE_FORMATTER.format(targetDate)} ($relative)"
    }

    private fun extractString(
        arguments: JsonObject,
        key: String,
    ): String? =
        (arguments[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun extractLimit(arguments: JsonObject): Int {
        val element = arguments["limit"] as? JsonPrimitive ?: return DEFAULT_LIMIT
        val parsed = element.intOrNull ?: element.content.trim().toIntOrNull() ?: return DEFAULT_LIMIT
        return parsed.coerceIn(MIN_LIMIT, MAX_LIMIT)
    }

    private fun isValidSnowflake(value: String): Boolean = value.all { it.isDigit() } && runCatching { Snowflake(value) }.isSuccess

    companion object {
        const val TOOL_NAME = "search_long_term_memory"
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 8
        const val DEFAULT_LIMIT = 5
        const val MAX_USER_UNDERSTANDINGS_IN_TOOL = 10
        const val EMPTY_RESULTS_NOTE = "近期 10 分鐘對話已在上下文中，且未找到符合門檻的較早長期記憶。"
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        val TOOL_DECLARATION: GeminiInteractionTool =
            GeminiInteractionTool.function(
                name = TOOL_NAME,
                description =
                    "Search Luna's guild-isolated long-term memory for older user preferences, personal facts, " +
                        "or shared guild context beyond the recent 10-minute working memory window. " +
                        "Skip for casual small talk; rewrite pronouns into standalone queries.",
                parameters =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("query") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Standalone semantic/keyword search query with pronouns resolved to names or topics.",
                                )
                            }
                            putJsonObject("limit") {
                                put("type", "integer")
                                put("description", "Maximum number of memories to return (clamped to 1..8, default 5).")
                            }
                            putJsonObject("scope") {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add(JsonPrimitive("user"))
                                    add(JsonPrimitive("guild"))
                                    add(JsonPrimitive("both"))
                                }
                                put(
                                    "description",
                                    "Memory scope: 'user' (user facts across guild), 'guild' (shared guild facts), or 'both' (default).",
                                )
                            }
                            putJsonObject("target_user_id") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Discord user Snowflake ID (e.g. from uid:<id> in the transcript). " +
                                        "Defaults to the triggering message author when scope is 'both' and omitted.",
                                )
                            }
                        }
                        put("required", JsonArray(listOf(JsonPrimitive("query"))))
                    },
            )

        fun invalidArgumentResponse(message: String): String =
            buildJsonObject {
                put("status", "invalid_argument")
                put("message", message)
            }.toString()

        fun limitReachedResponse(): String =
            buildJsonObject {
                put("status", "limit_reached")
                put("message", "已達本次回覆的長期記憶搜尋次數上限，請直接根據現有上下文與已取得的資訊回覆。")
            }.toString()

        fun unavailableResponse(): String =
            buildJsonObject {
                put("status", "unavailable")
                put("message", "長期記憶查詢暫時無法使用，請直接根據近期對話上下文回覆。")
            }.toString()
    }
}
