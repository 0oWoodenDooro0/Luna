package luna.core.ai

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.Guild
import dev.kord.core.entity.Member
import dev.kord.core.entity.Message
import dev.kord.core.entity.User
import dev.kord.core.entity.channel.Channel
import dev.kord.core.entity.channel.GuildChannel
import dev.kord.core.supplier.EntitySupplyStrategy
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory

/**
 * Resolved Discord mention and speaker display names for a single dialogue slice.
 *
 * Uses unified guild display-name priority (`effectiveName ?: globalName ?: username`) and
 * formats inline mentions according to [DialogueContextPolicy.includeMentionUids].
 */
class ResolvedSliceMentions(
    private val userNames: Map<String, String>,
    private val roleNames: Map<String, String>,
    private val channelNames: Map<String, String>,
    private val speakerNames: Map<Snowflake, String>,
    private val kordSelfId: Snowflake?,
    private val includeMentionUids: Boolean,
) {
    fun speakerDisplayName(authorId: Snowflake): String? = speakerNames[authorId]

    fun formatText(text: String): String {
        if (text.isEmpty() || !text.contains('<')) return text
        val selfIdStr = kordSelfId?.toString()
        var result =
            DiscordMentionResolver.USER_MENTION_REGEX.replace(text) { match ->
                val id = match.groupValues[1]
                when {
                    selfIdStr != null && id == selfIdStr -> {
                        if (includeMentionUids) "@Luna [YOU] (uid:$id)" else "@Luna"
                    }

                    else -> {
                        val name = userNames[id]
                        if (name != null) {
                            if (includeMentionUids) "@$name (uid:$id)" else "@$name"
                        } else {
                            match.value
                        }
                    }
                }
            }
        result =
            DiscordMentionResolver.ROLE_MENTION_REGEX.replace(result) { match ->
                val id = match.groupValues[1]
                val name = roleNames[id]
                if (name != null) {
                    if (includeMentionUids) "@$name (role)" else "@$name"
                } else {
                    match.value
                }
            }
        result =
            DiscordMentionResolver.CHANNEL_MENTION_REGEX.replace(result) { match ->
                val id = match.groupValues[1]
                val name = channelNames[id]
                if (name != null) "#$name" else match.value
            }
        return result
    }
}

/**
 * Collaborator responsible for resolving Discord user (`<@id>`, `<@!id>`), role (`<@&roleId>`),
 * and channel (`<#channelId>`) mentions as well as unified guild display names
 * (`effectiveName ?: globalName ?: username`) per dialogue slice.
 *
 * Resolution prefers message-embedded entities and Kord's local cache, deduplicates IDs per slice,
 * enforces [DialogueContextPolicy.maxMentionRestFallbacks], and degrades gracefully to the raw
 * token with observable warning logs on lookup failure or cap exhaustion.
 */
open class DiscordMentionResolver {
    private val logger = LoggerFactory.getLogger(DiscordMentionResolver::class.java)

    private class SliceResolutionState(
        val userNames: MutableMap<String, String> = linkedMapOf(),
        val roleNames: MutableMap<String, String> = linkedMapOf(),
        val channelNames: MutableMap<String, String> = linkedMapOf(),
        val speakerNames: MutableMap<Snowflake, String> = linkedMapOf(),
        val targetUserIds: MutableSet<String> = linkedSetOf(),
        val targetRoleIds: MutableSet<String> = linkedSetOf(),
        val targetChannelIds: MutableSet<String> = linkedSetOf(),
    )

    suspend fun resolveSlice(
        messages: List<Message>,
        extraTexts: List<String> = emptyList(),
        policy: DialogueContextPolicy,
        kordSelfId: Snowflake? = null,
    ): ResolvedSliceMentions {
        val selfIdStr = kordSelfId?.toString()
        val state = SliceResolutionState()

        for (msg in messages) {
            collectTokenIds(msg.content, selfIdStr, state)
            seedFromMessageEntities(msg, state)
        }
        for (text in extraTexts) {
            collectTokenIds(text, selfIdStr, state)
        }

        val kord = messages.firstNotNullOfOrNull { runCatching { it.kord }.getOrNull() }
        val cachedGuild =
            messages.firstNotNullOfOrNull { msg ->
                runCatching { msg.withStrategy(EntitySupplyStrategy.cache).getGuildOrNull() }.getOrNull()
            }

        resolveFromCache(
            state = state,
            messages = messages,
            kordSelfId = kordSelfId,
            kord = kord,
            cachedGuild = cachedGuild,
        )

        val unresolvedUserIds = state.targetUserIds.filterNot { it in state.userNames }
        val unresolvedRoleIds = state.targetRoleIds.filterNot { it in state.roleNames }
        val unresolvedChannelIds = state.targetChannelIds.filterNot { it in state.channelNames }

        if (unresolvedUserIds.isNotEmpty() || unresolvedRoleIds.isNotEmpty() || unresolvedChannelIds.isNotEmpty()) {
            resolveViaBoundedRest(
                unresolvedUserIds = unresolvedUserIds,
                unresolvedRoleIds = unresolvedRoleIds,
                unresolvedChannelIds = unresolvedChannelIds,
                messages = messages,
                kord = kord,
                policy = policy,
                state = state,
            )
        }

        return ResolvedSliceMentions(
            userNames = state.userNames,
            roleNames = state.roleNames,
            channelNames = state.channelNames,
            speakerNames = state.speakerNames,
            kordSelfId = kordSelfId,
            includeMentionUids = policy.includeMentionUids,
        )
    }

    private fun collectTokenIds(
        text: String,
        selfIdStr: String?,
        state: SliceResolutionState,
    ) {
        if (text.isEmpty() || !text.contains('<')) return
        USER_MENTION_REGEX.findAll(text).forEach { match ->
            val id = match.groupValues[1]
            if (selfIdStr == null || id != selfIdStr) {
                state.targetUserIds.add(id)
            }
        }
        ROLE_MENTION_REGEX.findAll(text).forEach { state.targetRoleIds.add(it.groupValues[1]) }
        CHANNEL_MENTION_REGEX.findAll(text).forEach { state.targetChannelIds.add(it.groupValues[1]) }
    }

    private suspend fun seedFromMessageEntities(
        msg: Message,
        state: SliceResolutionState,
    ) {
        val author = runCatching { msg.author }.getOrNull()
        if (author is Member) {
            author.effectiveName.takeIf { it.isNotBlank() }?.let { name ->
                state.speakerNames.putIfAbsent(author.id, name)
                state.userNames.putIfAbsent(author.id.toString(), name)
            }
        }

        val mentionedUsers = runCatching { msg.mentionedUsers.toList() }.getOrDefault(emptyList())
        for (user in mentionedUsers) {
            extractUserDisplayName(user)?.let { name ->
                state.userNames.putIfAbsent(user.id.toString(), name)
                if (user is Member) {
                    state.speakerNames.putIfAbsent(user.id, name)
                }
            }
        }

        val mentionedRoles = runCatching { msg.mentionedRoles.toList() }.getOrDefault(emptyList())
        for (role in mentionedRoles) {
            runCatching { role.name }.getOrNull()?.takeIf { it.isNotBlank() }?.let { name ->
                state.roleNames.putIfAbsent(role.id.toString(), name)
            }
        }

        val mentionedChannels = runCatching { msg.mentionedChannels.toList() }.getOrDefault(emptyList())
        for (channel in mentionedChannels) {
            extractChannelName(channel)?.let { validName ->
                state.channelNames.putIfAbsent(channel.id.toString(), validName)
            }
        }
    }

    private suspend fun resolveFromCache(
        state: SliceResolutionState,
        messages: List<Message>,
        kordSelfId: Snowflake?,
        kord: Kord?,
        cachedGuild: Guild?,
    ) {
        if (cachedGuild == null && kord == null) return

        for (idStr in state.targetUserIds) {
            val snowflake = Snowflake(idStr)
            // Prefer guild member effectiveName over already-seeded User globalName/username if cached
            val cachedMemberName =
                cachedGuild?.let {
                    runCatching { it.getMemberOrNull(snowflake)?.effectiveName }.getOrNull()?.takeIf { n -> n.isNotBlank() }
                }
            if (cachedMemberName != null) {
                state.userNames[idStr] = cachedMemberName
                state.speakerNames.putIfAbsent(snowflake, cachedMemberName)
            } else if (idStr !in state.userNames) {
                val cachedUserName =
                    kord?.let {
                        runCatching { it.getUser(snowflake, strategy = EntitySupplyStrategy.cache) }
                            .getOrNull()
                            ?.let(::extractUserDisplayName)
                    }
                if (cachedUserName != null) {
                    state.userNames[idStr] = cachedUserName
                }
            }
        }

        if (cachedGuild != null) {
            for (msg in messages) {
                val authorId = runCatching { msg.author?.id ?: msg.data.author.id }.getOrNull() ?: continue
                if (kordSelfId != null && authorId == kordSelfId) continue
                if (authorId in state.speakerNames) continue
                val cachedMemberName =
                    runCatching { cachedGuild.getMemberOrNull(authorId)?.effectiveName }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                if (cachedMemberName != null) {
                    state.speakerNames[authorId] = cachedMemberName
                }
            }
        }

        for (idStr in state.targetRoleIds) {
            if (idStr in state.roleNames) continue
            val roleName =
                cachedGuild?.let {
                    runCatching { it.getRoleOrNull(Snowflake(idStr))?.name }.getOrNull()?.takeIf { n -> n.isNotBlank() }
                }
            if (roleName != null) {
                state.roleNames[idStr] = roleName
            }
        }

        for (idStr in state.targetChannelIds) {
            if (idStr in state.channelNames) continue
            val snowflake = Snowflake(idStr)
            val channelName =
                cachedGuild?.let {
                    extractChannelName(runCatching { it.getChannelOrNull(snowflake) }.getOrNull())
                }
                    ?: kord?.let {
                        extractChannelName(
                            runCatching { it.getChannel(snowflake, strategy = EntitySupplyStrategy.cache) }.getOrNull(),
                        )
                    }
            if (channelName != null) {
                state.channelNames[idStr] = channelName
            }
        }
    }

    private suspend fun resolveViaBoundedRest(
        unresolvedUserIds: List<String>,
        unresolvedRoleIds: List<String>,
        unresolvedChannelIds: List<String>,
        messages: List<Message>,
        kord: Kord?,
        policy: DialogueContextPolicy,
        state: SliceResolutionState,
    ) {
        val restCap = policy.maxMentionRestFallbacks.coerceAtLeast(0)
        val restGuild: Guild? =
            if (restCap > 0) {
                messages.firstNotNullOfOrNull { msg ->
                    runCatching { msg.getGuildOrNull() }.getOrNull()
                }
            } else {
                null
            }
        var restFallbacksUsed = 0

        suspend fun attemptRest(
            kind: String,
            id: String,
            lookup: suspend () -> String?,
        ): String? {
            if (restCap <= 0) {
                logger.warn(
                    "Mention REST fallback disabled (maxMentionRestFallbacks={}) for {} {}; keeping raw token",
                    policy.maxMentionRestFallbacks,
                    kind,
                    id,
                )
                return null
            }
            if (restFallbacksUsed >= restCap) {
                logger.warn(
                    "Mention REST fallback cap exhausted (maxMentionRestFallbacks={}) for {} {}; keeping raw token",
                    restCap,
                    kind,
                    id,
                )
                return null
            }
            restFallbacksUsed++
            return try {
                val resolved = lookup()?.takeIf { it.isNotBlank() }
                if (resolved == null) {
                    logger.warn("Could not resolve {} mention {}; keeping raw token", kind, id)
                }
                resolved
            } catch (e: Exception) {
                logger.warn(
                    "Failed to resolve {} mention {} via REST: {}; keeping raw token",
                    kind,
                    id,
                    e.message ?: e::class.simpleName,
                )
                null
            }
        }

        for (idStr in unresolvedUserIds) {
            val snowflake = Snowflake(idStr)
            val name =
                attemptRest("user", idStr) {
                    val memberName = restGuild?.getMemberOrNull(snowflake)?.let(::extractUserDisplayName)
                    memberName ?: kord?.getUser(snowflake)?.let(::extractUserDisplayName)
                }
            if (name != null) {
                state.userNames[idStr] = name
            }
        }

        for (idStr in unresolvedRoleIds) {
            val snowflake = Snowflake(idStr)
            val name =
                attemptRest("role", idStr) {
                    restGuild?.getRoleOrNull(snowflake)?.name
                }
            if (name != null) {
                state.roleNames[idStr] = name
            }
        }

        for (idStr in unresolvedChannelIds) {
            val snowflake = Snowflake(idStr)
            val name =
                attemptRest("channel", idStr) {
                    extractChannelName(restGuild?.getChannelOrNull(snowflake))
                        ?: extractChannelName(kord?.getChannel(snowflake))
                }
            if (name != null) {
                state.channelNames[idStr] = name
            }
        }
    }

    companion object {
        val USER_MENTION_REGEX = Regex("""<@!?(\d+)>""")
        val ROLE_MENTION_REGEX = Regex("""<@&(\d+)>""")
        val CHANNEL_MENTION_REGEX = Regex("""<#(\d+)>""")

        fun extractUserDisplayName(user: User): String? =
            (user as? Member)?.effectiveName?.takeIf { it.isNotBlank() }
                ?: user.globalName?.takeIf { it.isNotBlank() }
                ?: user.username.takeIf { it.isNotBlank() }

        private fun extractChannelName(channel: Channel?): String? {
            if (channel == null) return null
            return (channel as? GuildChannel)?.let { runCatching { it.name }.getOrNull() }?.takeIf { it.isNotBlank() }
                ?: runCatching { channel.data.name.value }.getOrNull()?.takeIf { it.isNotBlank() }
        }

        fun extractSpeakerName(message: Message): String {
            val author = runCatching { message.author }.getOrNull()
            val memberEffective = (author as? Member)?.let { runCatching { it.effectiveName }.getOrNull() }?.takeIf { it.isNotBlank() }
            if (memberEffective != null) return memberEffective

            val globalName = author?.let { runCatching { it.globalName }.getOrNull() }?.takeIf { it.isNotBlank() }
            if (globalName != null) return globalName

            val username = author?.let { runCatching { it.username }.getOrNull() }?.takeIf { it.isNotBlank() }
            if (username != null) return username

            val dataUsername = runCatching { message.data.author.username }.getOrNull()?.takeIf { it.isNotBlank() }
            return dataUsername ?: "Unknown"
        }
    }
}
