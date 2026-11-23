package luna.chatter.memory

import kotlin.math.exp

/**
 * Combines lexical (`FTS5` / `LIKE`) and vector (`sqlite-vec`) candidate lists using
 * Reciprocal Rank Fusion (RRF) with bounded exponential recency decay.
 */
internal object ChatterMemoryRrfRanker {
    private const val RRF_K = 60.0
    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val RECENCY_DECAY_FLOOR = 0.7
    private const val RECENCY_DECAY_AMPLITUDE = 0.3
    private const val RECENCY_DECAY_SCALE_DAYS = 60.0

    fun fuseCandidatesWithRrf(
        lexicalCandidates: List<ChatterMemoryRecord>,
        vectorCandidates: List<SqliteChatterMemoryStore.VectorCandidate>,
        scoreCutoff: Double,
        limit: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ChatterMemorySearchHit> {
        val recordsById = LinkedHashMap<Long, ChatterMemoryRecord>()
        val lexicalRanks = HashMap<Long, Int>()
        val vectorRanks = HashMap<Long, Int>()
        val cosineById = HashMap<Long, Double>()

        lexicalCandidates.forEachIndexed { index, record ->
            recordsById.putIfAbsent(record.id, record)
            lexicalRanks[record.id] = index + 1
        }
        vectorCandidates.forEachIndexed { index, candidate ->
            recordsById.putIfAbsent(candidate.record.id, candidate.record)
            vectorRanks[candidate.record.id] = index + 1
            cosineById[candidate.record.id] = candidate.cosineSimilarity
        }

        return recordsById.values
            .mapNotNull { record ->
                val ftsRank = lexicalRanks[record.id]
                val vecRank = vectorRanks[record.id]
                val cosine = cosineById[record.id]
                val matchedByFts = ftsRank != null
                val matchedByVector = vecRank != null
                if (!matchedByFts && (cosine == null || cosine < scoreCutoff)) {
                    return@mapNotNull null
                }
                val rrfScore =
                    (if (ftsRank != null) 1.0 / (RRF_K + ftsRank) else 0.0) +
                        (if (vecRank != null) 1.0 / (RRF_K + vecRank) else 0.0)
                val ageDays = maxOf(0.0, (nowMs - record.updatedAt) / MILLIS_PER_DAY)
                val decayMultiplier = RECENCY_DECAY_FLOOR + RECENCY_DECAY_AMPLITUDE * exp(-ageDays / RECENCY_DECAY_SCALE_DAYS)
                val finalScore = rrfScore * decayMultiplier
                ChatterMemorySearchHit(
                    memory = record,
                    score = finalScore,
                    cosineSimilarity = cosine,
                    matchedByFts = matchedByFts,
                    matchedByVector = matchedByVector,
                )
            }.sortedWith(
                compareByDescending<ChatterMemorySearchHit> { it.score }
                    .thenByDescending { it.memory.updatedAt }
                    .thenByDescending { it.memory.id },
            ).take(limit)
    }
}
