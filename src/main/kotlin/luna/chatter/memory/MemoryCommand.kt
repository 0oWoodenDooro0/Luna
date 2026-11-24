package luna.chatter.memory

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.SubCommand
import dev.kord.rest.builder.interaction.boolean
import dev.kord.rest.builder.interaction.integer
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.subCommand
import luna.core.Command
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Self-service `/memory` slash command allowing users to list, delete, and clear their own
 * guild-scoped `user` memories in Chatter `Long-Term Memory`.
 */
class MemoryCommand(
    private val memoryStore: ChatterMemoryStore,
) : Command {
    override val name: String = "memory"
    override val description: String = "查看或刪除你在目前伺服器中的長期記憶"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            subCommand("list", "列出你在目前伺服器中的長期記憶") {
                integer("page", "頁碼（每頁 10 筆，預設第 1 頁）") {
                    required = false
                    minValue = 1
                }
            }
            subCommand("delete", "刪除你在目前伺服器中的指定長期記憶") {
                integer("id", "要刪除的記憶編號") {
                    required = true
                    minValue = 1
                }
                string("kind", "記憶類型（預設 fact 事實；understanding 印象）") {
                    required = false
                    choice("事實 (fact)", KIND_FACT)
                    choice("印象 (understanding)", KIND_UNDERSTANDING)
                }
            }
            subCommand("clear", "清除你在目前伺服器中的所有個人長期記憶") {
                boolean("confirm", "確認永久刪除所有個人長期記憶（需設為 true）") {
                    required = true
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
                "list" -> {
                    handleList(guildId = guildId, userId = userId, requestedPage = subCommand.integers["page"])
                }

                "delete" -> {
                    handleDelete(
                        guildId = guildId,
                        userId = userId,
                        memoryId = subCommand.integers["id"],
                        kind = subCommand.strings["kind"],
                    )
                }

                "clear" -> {
                    handleClear(guildId = guildId, userId = userId, confirm = subCommand.booleans["confirm"])
                }

                else -> {
                    "❌ 不支援的子指令。請使用 `/memory list`、`/memory delete <id>` 或 `/memory clear`。"
                }
            }

        response.respond {
            content = truncateForDiscord(replyContent)
        }
    }

    private fun handleList(
        guildId: String,
        userId: String,
        requestedPage: Long?,
    ): String {
        val items =
            runCatching {
                val facts =
                    memoryStore.listUserMemories(guildId = guildId, userId = userId).map { record ->
                        val updatedDate = formatUtcDate(record.updatedAt)
                        val expirySuffix = record.expiresAt?.let { ", expires ${formatUtcDate(it)}" }.orEmpty()
                        ListEntry(
                            updatedAt = record.updatedAt,
                            id = record.id,
                            line = "[事實 #${record.id}] ${record.content} (updated $updatedDate$expirySuffix)",
                        )
                    }
                val understandings =
                    memoryStore.listUserUnderstandings(guildId = guildId, userId = userId).map { record ->
                        val updatedDate = formatUtcDate(record.updatedAt)
                        ListEntry(
                            updatedAt = record.updatedAt,
                            id = record.id,
                            line = "[印象 #${record.id}] ${record.subject}: ${record.content} (updated $updatedDate)",
                        )
                    }
                (facts + understandings).sortedWith(
                    compareByDescending<ListEntry> { it.updatedAt }.thenByDescending { it.id },
                )
            }.onFailure { e ->
                logger.warn("Failed to list user memories for guild={} user={}: {}", guildId, userId, e.message)
            }.getOrElse {
                return "❌ 讀取長期記憶時發生錯誤，請稍後再試。"
            }

        if (items.isEmpty()) {
            return "ℹ️ 你在目前伺服器中尚未有任何長期記憶。"
        }

        val totalPages = (items.size + PAGE_SIZE - 1) / PAGE_SIZE
        val page = requestedPage ?: 1L
        if (page < 1L || page > totalPages.toLong()) {
            return "❌ 頁碼超出範圍，請輸入 `1` 到 `$totalPages` 之間的頁碼。"
        }

        val startIndex = ((page - 1L) * PAGE_SIZE).toInt()
        val pageItems = items.subList(startIndex, minOf(startIndex + PAGE_SIZE, items.size))

        return buildString {
            appendLine("🧠 **你在目前伺服器的長期記憶**（第 $page/$totalPages 頁，共 ${items.size} 筆）：")
            for (item in pageItems) {
                appendLine(item.line)
            }
        }.trimEnd()
    }

    private data class ListEntry(
        val updatedAt: Long,
        val id: Long,
        val line: String,
    )

    private fun formatUtcDate(epochMs: Long): String = DATE_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

    private fun handleDelete(
        guildId: String,
        userId: String,
        memoryId: Long?,
        kind: String? = null,
    ): String {
        if (memoryId == null || memoryId <= 0L) {
            return "❌ 請提供有效的記憶編號（正整數）。"
        }

        val resolvedKind = kind?.trim()?.lowercase() ?: KIND_FACT
        if (resolvedKind != KIND_FACT && resolvedKind != KIND_UNDERSTANDING) {
            return "❌ 不支援的記憶類型 `$kind`，請選擇 `$KIND_FACT` 或 `$KIND_UNDERSTANDING`。"
        }

        val deleted =
            runCatching {
                when (resolvedKind) {
                    KIND_UNDERSTANDING -> {
                        memoryStore.deleteOwnedUserUnderstanding(
                            id = memoryId,
                            guildId = guildId,
                            userId = userId,
                        )
                    }

                    else -> {
                        memoryStore.deleteOwnedUserMemory(
                            id = memoryId,
                            guildId = guildId,
                            userId = userId,
                        )
                    }
                }
            }.onFailure { e ->
                logger.warn(
                    "Failed to delete {} id={} for guild={} user={}: {}",
                    resolvedKind,
                    memoryId,
                    guildId,
                    userId,
                    e.message,
                )
            }.getOrElse {
                return "❌ 刪除長期記憶時發生錯誤，請稍後再試。"
            }

        val kindLabel = if (resolvedKind == KIND_UNDERSTANDING) "印象" else "記憶"
        return if (deleted) {
            "✅ 已永久刪除$kindLabel `#$memoryId`。"
        } else {
            "❌ 找不到$kindLabel `#$memoryId`，或你沒有權限刪除此$kindLabel。"
        }
    }

    private fun handleClear(
        guildId: String,
        userId: String,
        confirm: Boolean?,
    ): String {
        if (confirm != true) {
            return "⚠️ 已取消清除。若要永久刪除你在目前伺服器中的所有個人長期記憶，請將 `confirm:true` 設為確認。"
        }

        val deletedCount =
            runCatching {
                val factsDeleted = memoryStore.deleteAllOwnedUserMemories(guildId = guildId, userId = userId)
                val understandingsDeleted = memoryStore.deleteAllOwnedUserUnderstandings(guildId = guildId, userId = userId)
                factsDeleted + understandingsDeleted
            }.onFailure { e ->
                logger.warn("Failed to clear user memories for guild={} user={}: {}", guildId, userId, e.message)
            }.getOrElse {
                return "❌ 清除長期記憶時發生錯誤，請稍後再試。"
            }

        return "✅ 已永久清除你在目前伺服器中的 $deletedCount 筆個人長期記憶。"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MemoryCommand::class.java)
        private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE
        internal const val PAGE_SIZE = 10
        internal const val DISCORD_MAX_MESSAGE_LENGTH = 2000
        internal const val KIND_FACT = "fact"
        internal const val KIND_UNDERSTANDING = "understanding"

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
