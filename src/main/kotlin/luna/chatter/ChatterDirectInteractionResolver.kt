package luna.chatter

import dev.kord.common.entity.MessageType
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import luna.core.ai.AiTraceTrigger
import luna.core.ai.DiscordDialogueContextEngine
import org.slf4j.Logger
import org.slf4j.LoggerFactory

enum class DirectInteractionType {
    MENTION,
    DIRECT_REPLY,
    NONE,
    ;

    val isDirect: Boolean
        get() = this != NONE

    fun mergeWith(other: DirectInteractionType): DirectInteractionType =
        when {
            this == MENTION || other == MENTION -> MENTION
            this == DIRECT_REPLY || other == DIRECT_REPLY -> DIRECT_REPLY
            else -> NONE
        }

    fun toAiTraceTrigger(effectiveIsDirect: Boolean = isDirect): AiTraceTrigger =
        when {
            !effectiveIsDirect -> AiTraceTrigger.AMBIENT
            this == DIRECT_REPLY -> AiTraceTrigger.DIRECT_REPLY
            else -> AiTraceTrigger.MENTION
        }
}

/**
 * Resolves direct interactions with Luna (explicit @mentions and non-summary ping-off replies)
 * including bounded remote lookup of uncached reply targets.
 */
internal class ChatterDirectInteractionResolver(
    private val contextEngine: DiscordDialogueContextEngine = DiscordDialogueContextEngine(),
    private val referenceLookupTimeoutMs: Long = REFERENCE_LOOKUP_TIMEOUT_MS,
    private val logger: Logger = LoggerFactory.getLogger(ChatterDirectInteractionResolver::class.java),
) {
    fun isDirectBotMention(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): Boolean {
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull() ?: return false
        if (runCatching { message.mentionedUserIds.contains(resolvedSelfId) }.getOrDefault(false)) {
            return true
        }
        val content = message.content
        return content.contains("<@$resolvedSelfId>") || content.contains("<@!$resolvedSelfId>")
    }

    suspend fun classifyDirectInteraction(
        message: Message,
        kordSelfId: Snowflake? = null,
    ): DirectInteractionType {
        val resolvedSelfId = kordSelfId ?: runCatching { message.kord.selfId }.getOrNull() ?: return DirectInteractionType.NONE
        if (isDirectBotMention(message, resolvedSelfId)) {
            return DirectInteractionType.MENTION
        }
        if (isReplyToLuna(message, resolvedSelfId)) {
            return DirectInteractionType.DIRECT_REPLY
        }
        return DirectInteractionType.NONE
    }

    suspend fun isReplyToLuna(
        message: Message,
        kordSelfId: Snowflake? = null,
        resolveRemote: Boolean = true,
    ): Boolean {
        val resolvedSelfId =
            if (resolveRemote) {
                kordSelfId ?: runCatching { message.kord.selfId }.getOrNull()
            } else {
                kordSelfId
            }
        return matchesReplyTarget(message, resolvedSelfId) {
            if (resolveRemote) {
                resolveReferencedMessage(message)
            } else {
                runCatching { message.referencedMessage }.getOrNull()
            }
        }
    }

    fun isCachedReplyToLuna(
        message: Message,
        kordSelfId: Snowflake?,
    ): Boolean =
        matchesReplyTarget(message, kordSelfId) {
            runCatching { message.referencedMessage }.getOrNull()
        }

    private inline fun matchesReplyTarget(
        message: Message,
        resolvedSelfId: Snowflake?,
        resolveReference: () -> Message?,
    ): Boolean {
        val selfId = resolvedSelfId ?: return false
        val messageType = runCatching { message.type }.getOrNull()
        if (messageType != MessageType.Reply) {
            return false
        }
        val referenced = resolveReference() ?: return false
        return isNonSummaryReplyTarget(referenced, selfId)
    }

    private fun isNonSummaryReplyTarget(
        referenced: Message,
        selfId: Snowflake,
    ): Boolean {
        val referencedAuthorId =
            runCatching { referenced.author?.id }.getOrNull()
                ?: runCatching { referenced.data.author.id }.getOrNull()
                ?: return false
        if (referencedAuthorId != selfId) {
            return false
        }
        return !contextEngine.isBotSummaryMessage(referenced)
    }

    private suspend fun resolveReferencedMessage(message: Message): Message? {
        runCatching { message.referencedMessage }.getOrNull()?.let { return it }
        val ref = runCatching { message.messageReference }.getOrNull() ?: return null
        val refChannelId = runCatching { ref.data.channelId.value }.getOrNull()
        if (refChannelId != null && refChannelId != message.channelId) {
            return null
        }
        val refId = runCatching { ref.data.id.value }.getOrNull() ?: return null
        return try {
            var completed = false
            val resolved =
                withTimeoutOrNull(referenceLookupTimeoutMs) {
                    message.channel.getMessageOrNull(refId).also { completed = true }
                }
            if (!completed) {
                logger.debug(
                    "Timed out resolving referenced message {} for {} after {}ms",
                    refId,
                    message.id,
                    referenceLookupTimeoutMs,
                )
            }
            resolved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Failed to resolve referenced message {} for {}: {}", refId, message.id, e.message)
            null
        }
    }

    companion object {
        const val REFERENCE_LOOKUP_TIMEOUT_MS = 2_000L
    }
}
