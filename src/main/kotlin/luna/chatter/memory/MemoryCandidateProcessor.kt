package luna.chatter.memory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal data class FactCandidate(
    val scope: MemoryScope,
    val targetUserId: String?,
    val content: String,
    val expiresAt: Long? = null,
    val importance: Int = ChatterMemoryStore.DEFAULT_IMPORTANCE,
    val keywords: String = "",
)

/**
 * Pure helper functions for `Memory Distillation` candidate deduplication,
 * semantic `Fact Expiration` date clamping, and LLM JSON fence stripping.
 */
internal object MemoryCandidateProcessor {
    private val logger = LoggerFactory.getLogger(MemoryCandidateProcessor::class.java)
    private val MIN_EXPIRATION_MS = TimeUnit.DAYS.toMillis(1)
    private val MAX_EXPIRATION_MS = TimeUnit.DAYS.toMillis(365)
    private const val MIN_SUBSTRING_DEDUP_CHARS = 6
    private const val BATCH_DEDUP_JACCARD_THRESHOLD = 0.80
    private val NON_ALPHANUMERIC_SPACE_REGEX = Regex("""[^\p{L}\p{N}\s]""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun deduplicateBatchCandidates(candidates: List<FactCandidate>): List<FactCandidate> {
        if (candidates.size <= 1) return candidates
        val deduplicated = mutableListOf<FactCandidate>()
        for (candidate in candidates) {
            val matchIndex =
                deduplicated.indexOfFirst { existing ->
                    existing.scope == candidate.scope &&
                        existing.targetUserId == candidate.targetUserId &&
                        areNearIdentical(existing.content, candidate.content)
                }
            if (matchIndex < 0) {
                deduplicated.add(candidate)
            } else {
                val existing = deduplicated[matchIndex]
                val preferred =
                    if (candidate.content.length > existing.content.length) {
                        candidate.copy(
                            expiresAt = candidate.expiresAt ?: existing.expiresAt,
                            importance = maxOf(candidate.importance, existing.importance),
                            keywords = candidate.keywords.ifBlank { existing.keywords },
                        )
                    } else {
                        existing.copy(
                            expiresAt = existing.expiresAt ?: candidate.expiresAt,
                            importance = maxOf(existing.importance, candidate.importance),
                            keywords = existing.keywords.ifBlank { candidate.keywords },
                        )
                    }
                deduplicated[matchIndex] = preferred
            }
        }
        return deduplicated
    }

    fun parseAndClampExpiresAt(
        rawExpiresAt: String?,
        nowMs: Long,
        zone: ZoneId,
    ): Long? {
        val trimmed =
            rawExpiresAt?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                ?: return null
        val parsedDate =
            runCatching { LocalDate.parse(trimmed) }.getOrElse { ex ->
                logger.warn(
                    "Invalid expires_at date '{}' from memory distillation; degrading to null: {}",
                    trimmed,
                    ex.message,
                )
                return null
            }
        val endOfDayMs =
            parsedDate
                .atTime(23, 59, 59, 999_000_000)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        return endOfDayMs.coerceIn(nowMs + MIN_EXPIRATION_MS, nowMs + MAX_EXPIRATION_MS)
    }

    fun parseAndClampImportance(raw: JsonElement?): Int {
        if (raw == null || raw is JsonNull) return ChatterMemoryStore.DEFAULT_IMPORTANCE
        val parsed = (raw as? JsonPrimitive)?.intOrNull
        if (parsed == null) {
            logger.warn("Invalid importance value '{}' from memory distillation; defaulting to {}", raw, ChatterMemoryStore.DEFAULT_IMPORTANCE)
            return ChatterMemoryStore.DEFAULT_IMPORTANCE
        }
        return parsed.coerceIn(ChatterMemoryStore.MIN_IMPORTANCE, ChatterMemoryStore.MAX_IMPORTANCE)
    }

    fun parseAndSanitizeKeywords(raw: JsonElement?): String {
        val text =
            when (raw) {
                null, is JsonNull -> ""
                is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                is JsonPrimitive -> raw.contentOrNull.orEmpty()
                else -> ""
            }
        return sanitizeKeywords(text)
    }

    fun sanitizeKeywords(raw: String?): String {
        val normalized = raw?.trim()?.replace(WHITESPACE_REGEX, " ").orEmpty()
        if (normalized.isEmpty()) return ""
        return normalized.take(ChatterMemoryStore.MAX_KEYWORDS_CHARS).trim()
    }

    fun stripJsonFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutStart =
            trimmed
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .trimStart()
        return withoutStart.removeSuffix("```").trim()
    }

    private fun areNearIdentical(
        first: String,
        second: String,
    ): Boolean {
        val normA = normalizeForDedup(first)
        val normB = normalizeForDedup(second)
        if (normA.isEmpty() || normB.isEmpty()) return false
        if (normA == normB) return true

        val (shorter, longer) = if (normA.length <= normB.length) normA to normB else normB to normA
        if (shorter.length >= MIN_SUBSTRING_DEDUP_CHARS && longer.contains(shorter)) {
            return true
        }

        val tokensA = normA.split(' ').filter { it.isNotEmpty() }.toSet()
        val tokensB = normB.split(' ').filter { it.isNotEmpty() }.toSet()
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false
        val intersection = tokensA.intersect(tokensB).size
        val union = tokensA.union(tokensB).size
        return union > 0 && intersection.toDouble() / union.toDouble() >= BATCH_DEDUP_JACCARD_THRESHOLD
    }

    private fun normalizeForDedup(text: String): String =
        text
            .lowercase()
            .replace(NON_ALPHANUMERIC_SPACE_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
}
