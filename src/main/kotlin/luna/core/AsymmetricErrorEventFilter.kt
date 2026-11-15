package luna.core

import java.time.Clock
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Asymmetric source/severity [ErrorEventFilter] with bounded SLF4J / `JsonLogger` double-log correlation.
 *
 * - First-party loggers (`luna.*`, `dev.socialpeek.*`, `JSON_LOGGER`, `luna.core.UncaughtException`)
 *   pass both [ErrorAlertSeverity.WARN] and [ErrorAlertSeverity.ERROR].
 * - Third-party loggers (`dev.kord.*`, `io.ktor.*`, `io.netty.*`, Exposed/SQLite drivers, etc.)
 *   pass [ErrorAlertSeverity.ERROR] only; `WARN` and lower severities are rejected.
 * - Paired SLF4J + `JsonLogger` emissions for the same component and failure within
 *   [correlationWindowMs] suppress the redundant second emission (1-to-1) using bounded
 *   LRU + TTL correlation state per `CODING_STANDARDS.md` §1.
 */
class AsymmetricErrorEventFilter(
    private val clock: Clock = Clock.systemUTC(),
    private val correlationWindowMs: Long = DEFAULT_CORRELATION_WINDOW_MS,
    maxTrackedEntries: Int = DEFAULT_MAX_TRACKED_ENTRIES,
) : ErrorEventFilter {
    private enum class LogChannel {
        SLF4J,
        JSON_LOGGER,
    }

    private data class ExtractedSignals(
        val channel: LogChannel,
        val componentKey: String?,
        val traceId: String?,
        val guildId: String?,
        val channelId: String?,
        val contextIds: Map<String, String>,
        val messageLower: String,
        val fullMessageLower: String,
        val searchableTextLower: String,
        val exceptionMessageLower: String?,
        val errorDetailLower: String?,
    )

    private data class TrackedEmission(
        val id: Long,
        val recordedAtMs: Long,
        val signals: ExtractedSignals,
    )

    private val boundedCapacity: Int = maxTrackedEntries.coerceAtLeast(1)
    private val effectiveWindowMs: Long = correlationWindowMs.coerceAtLeast(0L)
    private val sequence = AtomicLong(0L)

    private val recentEmissions: MutableMap<Long, TrackedEmission> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Long, TrackedEmission>(boundedCapacity, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TrackedEmission>?): Boolean = size > boundedCapacity
            },
        )

    override fun shouldNotify(event: OwnerErrorEvent): Boolean {
        val passesSeverityPolicy =
            when (event.severity) {
                ErrorAlertSeverity.ERROR -> true
                ErrorAlertSeverity.WARN -> isFirstPartyLogger(event.loggerName)
            }
        if (!passesSeverityPolicy) return false

        if (effectiveWindowMs <= 0L) return true
        // Third-party loggers do not emit paired JsonLogger events; avoid polluting correlation cache.
        if (!isFirstPartyLogger(event.loggerName)) return true

        val incomingSignals = extractSignals(event)
        if (incomingSignals.componentKey == null) return true

        val nowMs = clock.millis()
        synchronized(recentEmissions) {
            evictExpiredLocked(nowMs)

            var bestMatchId: Long? = null
            var bestScore = 0
            for ((id, tracked) in recentEmissions) {
                if (tracked.signals.channel == incomingSignals.channel) continue
                val score = scoreCorrelation(tracked.signals, incomingSignals)
                if (score >= MIN_CORRELATION_SCORE && score >= bestScore) {
                    bestScore = score
                    bestMatchId = id
                }
            }

            if (bestMatchId != null) {
                recentEmissions.remove(bestMatchId)
                return false
            }

            val id = sequence.incrementAndGet()
            recentEmissions[id] =
                TrackedEmission(
                    id = id,
                    recordedAtMs = nowMs,
                    signals = incomingSignals,
                )
            return true
        }
    }

    private fun evictExpiredLocked(nowMs: Long) {
        val iterator = recentEmissions.entries.iterator()
        while (iterator.hasNext()) {
            val elapsedMs = nowMs - iterator.next().value.recordedAtMs
            if (elapsedMs > effectiveWindowMs || elapsedMs < 0L) {
                iterator.remove()
            }
        }
    }

    private fun scoreCorrelation(
        existing: ExtractedSignals,
        incoming: ExtractedSignals,
    ): Int {
        if (existing.componentKey == null || incoming.componentKey == null) return -1
        if (existing.componentKey != incoming.componentKey) return -1

        if (existing.traceId != null && incoming.traceId != null && existing.traceId != incoming.traceId) return -1
        if (existing.guildId != null && incoming.guildId != null && existing.guildId != incoming.guildId) return -1
        if (existing.channelId != null && incoming.channelId != null && existing.channelId != incoming.channelId) return -1

        for ((key, value) in existing.contextIds) {
            val otherValue = incoming.contextIds[key]
            if (otherValue != null && otherValue != value) return -1
        }

        val slf4j = if (existing.channel == LogChannel.SLF4J) existing else incoming
        val json = if (existing.channel == LogChannel.JSON_LOGGER) existing else incoming

        var score = 0
        if (slf4j.traceId != null && slf4j.traceId == json.traceId) score += 4
        if (slf4j.guildId != null && slf4j.guildId == json.guildId) score += 3
        if (slf4j.channelId != null && slf4j.channelId == json.channelId) score += 3

        for ((key, value) in json.contextIds) {
            if (slf4j.contextIds[key] == value) {
                score += 4
            } else if (value.length >= MIN_TOKEN_MATCH_LENGTH && slf4j.searchableTextLower.contains(value.lowercase())) {
                score += 3
            }
        }

        val jsonDetail = json.errorDetailLower
        val slf4jExceptionMsg = slf4j.exceptionMessageLower
        var detailMatched = false

        if (jsonDetail != null && jsonDetail.length >= MIN_DETAIL_MATCH_LENGTH) {
            if (slf4j.searchableTextLower.contains(jsonDetail)) {
                score += 5
                detailMatched = true
            } else if (slf4jExceptionMsg != null &&
                slf4jExceptionMsg.length >= MIN_DETAIL_MATCH_LENGTH &&
                jsonDetail.contains(slf4jExceptionMsg)
            ) {
                score += 4
                detailMatched = true
            } else if (slf4jExceptionMsg != null && slf4jExceptionMsg.length >= MIN_DETAIL_MATCH_LENGTH) {
                // Both events explicitly carry different exception/error messages
                return -1
            }
        } else if (slf4jExceptionMsg != null &&
            slf4jExceptionMsg.length >= MIN_DETAIL_MATCH_LENGTH &&
            json.fullMessageLower.contains(slf4jExceptionMsg)
        ) {
            score += 4
            detailMatched = true
        } else if (slf4j.messageLower.length >= MIN_DETAIL_MATCH_LENGTH &&
            json.fullMessageLower.contains(slf4j.messageLower)
        ) {
            score += 4
            detailMatched = true
        }

        if (!detailMatched && jsonDetail != null && slf4j.messageLower.isNotBlank() && score <= 4) {
            return -1
        }

        return score
    }

    private fun extractSignals(event: OwnerErrorEvent): ExtractedSignals {
        val channel =
            if (event.loggerName.trim() == JSON_LOGGER_NAME) {
                LogChannel.JSON_LOGGER
            } else {
                LogChannel.SLF4J
            }

        val componentKey = resolveComponentKey(event, channel)
        val contextIds = buildContextIds(event, channel)

        val messageTrimmed = event.message.trim()
        val messageLower = messageTrimmed.lowercase()
        val searchableTextLower =
            buildString {
                append(messageLower)
                val trace = event.stackTrace?.trim()
                if (!trace.isNullOrEmpty()) {
                    append('\n')
                    append(trace.lowercase())
                }
            }

        val exceptionMessageLower =
            if (channel == LogChannel.SLF4J) {
                extractExceptionMessage(event.stackTrace)?.lowercase()
            } else {
                null
            }

        val errorDetailLower =
            if (channel == LogChannel.JSON_LOGGER) {
                extractJsonErrorDetail(messageTrimmed)?.lowercase()
            } else {
                null
            }

        return ExtractedSignals(
            channel = channel,
            componentKey = componentKey,
            traceId = event.traceId?.trim()?.ifEmpty { null },
            guildId = event.guildId?.trim()?.ifEmpty { null } ?: contextIds["guildId"],
            channelId = event.channelId?.trim()?.ifEmpty { null } ?: contextIds["channelId"],
            contextIds = contextIds,
            messageLower = messageLower,
            fullMessageLower = messageLower,
            searchableTextLower = searchableTextLower,
            exceptionMessageLower = exceptionMessageLower,
            errorDetailLower = errorDetailLower,
        )
    }

    private fun resolveComponentKey(
        event: OwnerErrorEvent,
        channel: LogChannel,
    ): String? {
        val explicitComponent = event.component?.trim()?.ifEmpty { null }
        if (explicitComponent != null) return explicitComponent.lowercase()

        if (channel == LogChannel.JSON_LOGGER) {
            val match = JSON_COMPONENT_REGEX.find(event.message)
            val extracted =
                match
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    ?.ifEmpty { null }
            return extracted?.lowercase()
        }

        val rawLogger = event.loggerName.trim()
        if (rawLogger.isEmpty()) return null
        val simpleName = rawLogger.substringAfterLast('.').substringBefore('$').trim()
        return simpleName.ifEmpty { null }?.lowercase()
    }

    private fun buildContextIds(
        event: OwnerErrorEvent,
        channel: LogChannel,
    ): Map<String, String> {
        val ids = linkedMapOf<String, String>()
        event.contextFields.forEach { (rawKey, rawValue) ->
            val key = normalizeContextKey(rawKey)
            val trimmed = rawValue.trim()
            if (trimmed.isNotEmpty()) ids[key] = trimmed
        }

        if (channel == LogChannel.JSON_LOGGER) {
            val dataMatch = JSON_DATA_BLOCK_REGEX.find(event.message)
            val dataBody = dataMatch?.groupValues?.getOrNull(1)
            if (!dataBody.isNullOrBlank()) {
                for (entry in dataBody.split(',')) {
                    val eqIdx = entry.indexOf('=')
                    if (eqIdx <= 0) continue
                    val key = normalizeContextKey(entry.substring(0, eqIdx).trim())
                    val value = entry.substring(eqIdx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty() && key != "error") {
                        ids.putIfAbsent(key, value)
                    }
                }
            }
        } else {
            if ("messageId" !in ids) {
                SLF4J_MESSAGE_ID_REGEX.find(event.message)?.groupValues?.getOrNull(1)?.let { msgId ->
                    ids["messageId"] = msgId
                }
            }
            if ("url" !in ids) {
                URL_REGEX.find(event.message)?.value?.let { url ->
                    ids["url"] = url
                }
            }
        }

        return ids
    }

    private fun normalizeContextKey(key: String): String =
        when (key) {
            "message_id" -> "messageId"
            "guild_id" -> "guildId"
            "channel_id" -> "channelId"
            "user_id" -> "userId"
            "author_id" -> "authorId"
            "trace_id" -> "traceId"
            else -> key
        }

    private fun extractExceptionMessage(stackTrace: String?): String? {
        val firstLine = stackTrace?.lineSequence()?.firstOrNull()?.trim() ?: return null
        val colonIdx = firstLine.indexOf(':')
        if (colonIdx < 0 || colonIdx + 1 >= firstLine.length) return null
        return firstLine.substring(colonIdx + 1).trim().ifEmpty { null }
    }

    private fun extractJsonErrorDetail(message: String): String? {
        val pipeIdx = message.lastIndexOf(" | ")
        if (pipeIdx >= 0 && pipeIdx + 3 < message.length) {
            return message.substring(pipeIdx + 3).trim().ifEmpty { null }
        }
        if (!message.startsWith("Layer:")) {
            return message.ifEmpty { null }
        }
        val dataMatch = JSON_DATA_BLOCK_REGEX.find(message)
        val dataBody = dataMatch?.groupValues?.getOrNull(1) ?: return null
        for (entry in dataBody.split(',')) {
            val eqIdx = entry.indexOf('=')
            if (eqIdx <= 0) continue
            val key = entry.substring(0, eqIdx).trim()
            val value = entry.substring(eqIdx + 1).trim()
            if (key == "error" && value.isNotEmpty()) return value
        }
        return null
    }

    companion object {
        const val DEFAULT_CORRELATION_WINDOW_MS = 2_000L
        const val DEFAULT_MAX_TRACKED_ENTRIES = 512
        const val JSON_LOGGER_NAME = "JSON_LOGGER"
        const val UNCAUGHT_EXCEPTION_LOGGER_NAME = "luna.core.UncaughtException"

        private const val MIN_CORRELATION_SCORE = 3
        private const val MIN_TOKEN_MATCH_LENGTH = 3
        private const val MIN_DETAIL_MATCH_LENGTH = 4

        private val FIRST_PARTY_EXACT_LOGGERS =
            setOf(
                JSON_LOGGER_NAME,
                UNCAUGHT_EXCEPTION_LOGGER_NAME,
                "luna",
                "dev.socialpeek",
            )

        private val FIRST_PARTY_PREFIXES =
            listOf(
                "luna.",
                "dev.socialpeek.",
            )

        private val JSON_COMPONENT_REGEX = Regex("""Component:\s*(?:component=)?([^\s,]+)""")
        private val JSON_DATA_BLOCK_REGEX = Regex("""data=\{([^}]*)\}""")
        private val SLF4J_MESSAGE_ID_REGEX = Regex("""\bmessage\s+(\d+)\b""", RegexOption.IGNORE_CASE)
        private val URL_REGEX = Regex("""https?://[^\s,]+""")

        fun isFirstPartyLogger(loggerName: String): Boolean {
            val trimmed = loggerName.trim()
            if (trimmed.isEmpty()) return false
            if (trimmed in FIRST_PARTY_EXACT_LOGGERS) return true
            return FIRST_PARTY_PREFIXES.any { prefix -> trimmed.startsWith(prefix) }
        }
    }
}
