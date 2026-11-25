package luna.chatter.memory

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Zero-embedding pre-injector that loads compact `Social Understanding` impressions directly from
 * [ChatterMemoryStore] for `ChatterGenerator` reply prompts within a soft character budget.
 */
open class UnderstandingPreInjector(
    internal val memoryStore: ChatterMemoryStore,
    private val maxChars: Int = ChatterMemoryConfig.DEFAULT_UNDERSTANDING_PRE_INJECT_MAX_CHARS,
) {
    private val logger = LoggerFactory.getLogger(UnderstandingPreInjector::class.java)

    /**
     * Fetches `Social Understanding` entries for the triggering [authorId] (up to 10), up to 2 other
     * recent human speakers parsed from [transcript] (up to 5 each), and the active [guildId] (up to 8),
     * bounded by [maxChars] and touching `last_accessed_at` for injected entries.
     *
     * Fails open with a warning log and returns `null` if SQLite lookup fails.
     */
    open fun buildPreInjectionSection(
        guildId: String?,
        authorId: String?,
        transcript: String,
    ): String? =
        buildPreInjectionSection(
            guildId = guildId,
            authorId = authorId,
            transcript = transcript,
            selfId = null,
        )

    open fun buildPreInjectionSection(
        guildId: String?,
        authorId: String?,
        transcript: String,
        selfId: String? = null,
    ): String? {
        val cleanGuildId = guildId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (maxChars <= 0) return null

        val cleanAuthorId = authorId?.trim()?.takeIf { it.isNotEmpty() }
        val otherSpeakers =
            extractRecentOtherHumanSpeakers(
                transcript = transcript,
                excludeAuthorId = cleanAuthorId,
                selfId = selfId,
            )

        val speakerIds =
            buildList {
                if (cleanAuthorId != null) add(cleanAuthorId)
                otherSpeakers.forEach { add(it.userId) }
            }
        val relationships =
            if (speakerIds.isEmpty()) {
                emptyMap()
            } else {
                try {
                    memoryStore.listUserRelationships(
                        guildId = cleanGuildId,
                        userIds = speakerIds,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(
                        "UnderstandingPreInjector failed open during UserRelationship lookup (guildId={}): {}",
                        cleanGuildId,
                        e.message,
                    )
                    emptyMap()
                }
            }

        val groups =
            try {
                buildList<UnderstandingGroup> {
                    if (cleanAuthorId != null) {
                        val entries =
                            memoryStore.listUserUnderstandings(
                                guildId = cleanGuildId,
                                userId = cleanAuthorId,
                                limit = AUTHOR_LIMIT,
                            )
                        val relLine = formatRelationshipLine(relationships[cleanAuthorId])
                        if (relLine != null || entries.isNotEmpty()) {
                            add(
                                UnderstandingGroup(
                                    header = "[當前發言者 (uid:$cleanAuthorId)]",
                                    relationshipLine = relLine,
                                    entries = entries,
                                ),
                            )
                        }
                    }
                    for (speaker in otherSpeakers) {
                        val entries =
                            memoryStore.listUserUnderstandings(
                                guildId = cleanGuildId,
                                userId = speaker.userId,
                                limit = OTHER_SPEAKER_ENTRY_LIMIT,
                            )
                        val relLine = formatRelationshipLine(relationships[speaker.userId])
                        if (relLine != null || entries.isNotEmpty()) {
                            add(
                                UnderstandingGroup(
                                    header = "[近期群友 ${speaker.displayName} (uid:${speaker.userId})]",
                                    relationshipLine = relLine,
                                    entries = entries,
                                ),
                            )
                        }
                    }
                    val guildEntries =
                        memoryStore.listGuildUnderstandings(
                            guildId = cleanGuildId,
                            limit = GUILD_LIMIT,
                        )
                    if (guildEntries.isNotEmpty()) {
                        add(UnderstandingGroup(header = "[本伺服器印象]", entries = guildEntries))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "UnderstandingPreInjector failed open during Social Understanding lookup (guildId={}): {}",
                    cleanGuildId,
                    e.message,
                )
                return null
            }

        if (groups.isEmpty()) return null

        val sb = StringBuilder(SECTION_HEADER)
        if (sb.length > maxChars) return null

        val touchedIds = mutableListOf<Long>()
        var hasContent = false
        var budgetExhausted = false
        for (group in groups) {
            if (budgetExhausted) break
            val headerLine = "\n${group.header}"
            var headerAppended = false
            val relLine = group.relationshipLine
            if (relLine != null) {
                val needed = relLine.length + if (headerAppended) 0 else headerLine.length
                if (sb.length + needed > maxChars) {
                    budgetExhausted = true
                    break
                }
                if (!headerAppended) {
                    sb.append(headerLine)
                    headerAppended = true
                }
                sb.append(relLine)
                hasContent = true
            }
            for (entry in group.entries) {
                val entryLine = "\n- ${entry.subject}：${entry.content}"
                val needed = entryLine.length + if (headerAppended) 0 else headerLine.length
                if (sb.length + needed > maxChars) {
                    budgetExhausted = true
                    break
                }
                if (!headerAppended) {
                    sb.append(headerLine)
                    headerAppended = true
                }
                sb.append(entryLine)
                touchedIds.add(entry.id)
                hasContent = true
            }
        }

        if (!hasContent) return null

        if (touchedIds.isNotEmpty()) {
            try {
                memoryStore.touchUnderstandings(cleanGuildId, touchedIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "UnderstandingPreInjector failed to touch last_accessed_at (guildId={}, count={}): {}",
                    cleanGuildId,
                    touchedIds.size,
                    e.message,
                )
            }
        }

        return sb.toString()
    }

    internal fun extractRecentOtherHumanSpeakers(
        transcript: String,
        excludeAuthorId: String?,
        selfId: String? = null,
    ): List<SpeakerRef> {
        if (transcript.isBlank()) return emptyList()
        val lines = transcript.lines()
        val nonHumanIds = HashSet<String>()
        selfId?.trim()?.takeIf { it.isNotEmpty() }?.let { nonHumanIds.add(it) }
        for (line in lines) {
            val match = SPEAKER_HEADER_REGEX.find(line) ?: continue
            val rawName = match.groupValues[1].trim()
            val uid = match.groupValues[2].trim()
            if (uid.isNotEmpty() && isNonHumanName(rawName)) {
                nonHumanIds.add(uid)
            }
        }

        val seenIds = HashSet<String>()
        if (excludeAuthorId != null) {
            seenIds.add(excludeAuthorId)
        }
        val speakers = mutableListOf<SpeakerRef>()

        val lastSpeakerIdx = lines.indexOfLast { SPEAKER_HEADER_REGEX.containsMatchIn(it) }
        val latestMessageLines = if (lastSpeakerIdx >= 0) lines.subList(lastSpeakerIdx, lines.size) else lines.takeLast(1)
        for (line in latestMessageLines) {
            for (match in MENTION_REGEX.findAll(line)) {
                val rawName = match.groupValues[1].trim()
                val uid = match.groupValues[2].trim()
                if (uid.isEmpty() || isNonHumanName(rawName) || uid in nonHumanIds) {
                    continue
                }
                if (seenIds.add(uid)) {
                    speakers.add(SpeakerRef(userId = uid, displayName = rawName))
                    if (speakers.size >= OTHER_SPEAKER_COUNT_LIMIT) {
                        return speakers
                    }
                }
            }
        }

        for (line in lines.asReversed()) {
            val match = SPEAKER_HEADER_REGEX.find(line) ?: continue
            val rawName = match.groupValues[1].trim()
            val uid = match.groupValues[2].trim()
            if (uid.isEmpty() || isNonHumanName(rawName) || uid in nonHumanIds) {
                continue
            }
            if (seenIds.add(uid)) {
                speakers.add(SpeakerRef(userId = uid, displayName = rawName))
                if (speakers.size >= OTHER_SPEAKER_COUNT_LIMIT) {
                    break
                }
            }
        }
        return speakers
    }

    private fun isNonHumanName(rawName: String): Boolean =
        rawName.endsWith(BOT_SUFFIX) ||
            rawName == LUNA_NAME ||
            rawName == LUNA_YOU_NAME ||
            rawName.endsWith(YOU_SUFFIX)

    internal data class SpeakerRef(
        val userId: String,
        val displayName: String,
    )

    private fun formatRelationshipLine(relationship: UserRelationship?): String? {
        if (relationship == null) return null
        val tier = relationship.tier
        val cleanTone = relationship.toneStyle.trim()
        return when {
            tier != AffinityTier.NEUTRAL && cleanTone.isNotEmpty() -> "\n- 關係與互動語氣：${tier.name}（$cleanTone）"
            tier != AffinityTier.NEUTRAL -> "\n- 關係傾向：${tier.name}"
            cleanTone.isNotEmpty() -> "\n- 互動風格：$cleanTone"
            else -> null
        }
    }

    private data class UnderstandingGroup(
        val header: String,
        val relationshipLine: String? = null,
        val entries: List<SocialUnderstandingRecord>,
    )

    companion object {
        const val AUTHOR_LIMIT = 10
        const val OTHER_SPEAKER_COUNT_LIMIT = 2
        const val OTHER_SPEAKER_ENTRY_LIMIT = 5
        const val GUILD_LIMIT = 8
        private const val BOT_SUFFIX = "[BOT]"
        private const val YOU_SUFFIX = "[YOU]"
        private const val LUNA_NAME = "Luna"
        private const val LUNA_YOU_NAME = "Luna [YOU]"
        private const val SECTION_HEADER =
            "以下為你對相關群友與本伺服器的長期印象（僅作自然語氣與互動參考，勿生硬複述）："
        private val SPEAKER_HEADER_REGEX = Regex("""^(.+?)\s+\(uid:(\d+)\)(?::|\s|$)""")
        private val MENTION_REGEX = Regex("""@([^@\n]+?)\s+\(uid:(\d+)\)""")
    }
}
