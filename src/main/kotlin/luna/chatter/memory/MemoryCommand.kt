package luna.chatter.memory

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.SubCommand
import dev.kord.rest.builder.interaction.integer
import dev.kord.rest.builder.interaction.subCommand
import luna.core.Command
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Self-service `/memory` slash command allowing users to list and delete their own
 * guild-scoped `user` memories in Chatter `Long-Term Memory`.
 */
class MemoryCommand(
    private val memoryStore: ChatterMemoryStore,
) : Command {
    override val name: String = "memory"
    override val description: String = "查看或刪除你在目前伺服器中的長期記憶"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            subCommand("list", "列出你在目前伺服器中的長期記憶")
            subCommand("delete", "刪除你在目前伺服器中的指定長期記憶") {
                integer("id", "要刪除的記憶編號") {
                    required = true
                    minValue = 1
                }
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val response = interaction.deferEphemeralResponse()

        val rawGuildId = interaction.data.guildId.value
        if (rawGuildId == null) {
            response.respond {
                content = "此指令只能在伺服器中使用！"
            }
            return
        }

        val guildId = rawGuildId.toString()
        val userId = interaction.user.id.toString()
        val subCommand = interaction.command as? SubCommand

        val replyContent =
            when (subCommand?.name) {
                "list" -> handleList(guildId = guildId, userId = userId)
                "delete" -> handleDelete(guildId = guildId, userId = userId, memoryId = subCommand.integers["id"])
                else -> "❌ 不支援的子指令。請使用 `/memory list` 或 `/memory delete <id>`。"
            }

        response.respond {
            content = truncateForDiscord(replyContent)
        }
    }

    private fun handleList(
        guildId: String,
        userId: String,
    ): String {
        val memories =
            runCatching {
                memoryStore.listUserMemories(guildId = guildId, userId = userId)
            }.onFailure { e ->
                logger.warn("Failed to list user memories for guild={} user={}: {}", guildId, userId, e.message)
            }.getOrElse {
                return "❌ 讀取長期記憶時發生錯誤，請稍後再試。"
            }

        if (memories.isEmpty()) {
            return "ℹ️ 你在目前伺服器中尚未有任何長期記憶。"
        }

        return buildString {
            appendLine("🧠 **你在目前伺服器的長期記憶**（共 ${memories.size} 筆）：")
            for (record in memories) {
                val updatedDate =
                    DATE_FORMATTER.format(
                        Instant.ofEpochMilli(record.updatedAt).atZone(ZoneOffset.UTC),
                    )
                appendLine("[#${record.id}] ${record.content} (updated $updatedDate)")
            }
        }.trimEnd()
    }

    private fun handleDelete(
        guildId: String,
        userId: String,
        memoryId: Long?,
    ): String {
        if (memoryId == null || memoryId <= 0L) {
            return "❌ 請提供有效的記憶編號（正整數）。"
        }

        val deleted =
            runCatching {
                memoryStore.deleteOwnedUserMemory(
                    id = memoryId,
                    guildId = guildId,
                    userId = userId,
                )
            }.onFailure { e ->
                logger.warn(
                    "Failed to delete memory id={} for guild={} user={}: {}",
                    memoryId,
                    guildId,
                    userId,
                    e.message,
                )
            }.getOrElse {
                return "❌ 刪除長期記憶時發生錯誤，請稍後再試。"
            }

        return if (deleted) {
            "✅ 已永久刪除記憶 `#$memoryId`。"
        } else {
            "❌ 找不到記憶 `#$memoryId`，或你沒有權限刪除此記憶。"
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MemoryCommand::class.java)
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
        internal const val DISCORD_MAX_MESSAGE_LENGTH = 2000

        internal fun truncateForDiscord(
            message: String,
            maxLength: Int = DISCORD_MAX_MESSAGE_LENGTH,
        ): String {
            if (message.length <= maxLength) {
                return message
            }
            if (maxLength <= 3) {
                return "...".take(maxLength)
            }
            return message.take(maxLength - 3) + "..."
        }
    }
}
