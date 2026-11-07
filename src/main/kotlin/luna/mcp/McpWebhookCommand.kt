package luna.mcp

import dev.kord.common.entity.Permission
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.entity.channel.TopGuildChannel
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.GroupCommand
import dev.kord.core.entity.interaction.SubCommand
import dev.kord.rest.builder.interaction.group
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.subCommand
import dev.kord.rest.service.WebhookService
import luna.core.Command
import luna.core.JsonLogger
import luna.mcp.data.McpClientRecord
import luna.mcp.data.McpClientRepository
import luna.mcp.data.McpWebhookRecord
import luna.mcp.data.McpWebhookRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

suspend fun WebhookService.deleteWebhook(
    id: Snowflake,
    token: String,
    reason: String? = null,
) {
    deleteWebhookWithToken(id, token, reason)
}

class McpWebhookCommand(
    private val clientRepository: McpClientRepository,
    private val webhookRepository: McpWebhookRepository,
) : Command {
    override val name: String = "mcp"
    override val description: String = "Manage Model Context Protocol (MCP) clients and webhooks"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            group("webhook", "Manage MCP channel webhooks") {
                subCommand("add", "Add or auto-provision a Discord webhook for MCP") {
                    string("tag", "Tag name for this webhook") {
                        required = true
                    }
                    string("url", "Custom Discord Webhook URL (optional)") {
                        required = false
                    }
                }
                subCommand("remove", "Remove a registered MCP webhook") {
                    string("tag", "Tag name of the webhook to remove") {
                        required = true
                    }
                }
                subCommand("list", "List all registered webhooks and quota usage") {}
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val groupCommand = interaction.command as? GroupCommand
        if (groupCommand != null && groupCommand.groupName != "webhook") {
            interaction.respondEphemeral {
                content = "Unknown command. Please use `/mcp webhook add`, `/mcp webhook remove`, or `/mcp webhook list`."
            }
            return
        }

        val subcommandName =
            when (val cmd = interaction.command) {
                is GroupCommand -> cmd.name
                is SubCommand -> cmd.name
                else -> null
            }

        val strings =
            when (val cmd = interaction.command) {
                is GroupCommand -> cmd.strings
                is SubCommand -> cmd.strings
                else -> emptyMap()
            }

        when (subcommandName) {
            "add" -> {
                handleWebhookAdd(interaction, strings)
            }

            "remove" -> {
                handleWebhookRemove(interaction, strings)
            }

            "list" -> {
                handleWebhookList(interaction)
            }

            else -> {
                interaction.respondEphemeral {
                    content = "Unknown subcommand. Please use `/mcp webhook add`, `/mcp webhook remove`, or `/mcp webhook list`."
                }
            }
        }
    }

    private suspend fun handleWebhookAdd(
        interaction: ChatInputCommandInteraction,
        strings: Map<String, String>,
    ) {
        val userId = interaction.user.id.toString()
        val client = clientRepository.findByUserId(userId)
        if (client == null) {
            interaction.respondEphemeral {
                content = "❌ 您尚未建立 Client ID，請先執行 `/mcp client create` 建立金鑰。"
            }
            return
        }

        val rawTag = strings["tag"]?.trim()
        if (rawTag.isNullOrEmpty() || rawTag.length !in 1..32 || !TAG_REGEX.matches(rawTag)) {
            interaction.respondEphemeral {
                content = "❌ 標籤格式不正確。標籤長度需為 1 至 32 個字元，且僅能包含英數字、底線、連字號與中文，不可包含空白。"
            }
            return
        }
        val tag = rawTag

        val currentWebhooks = webhookRepository.listByClientId(client.clientId)
        if (currentWebhooks.size >= MAX_WEBHOOKS_PER_CLIENT) {
            interaction.respondEphemeral {
                content = "❌ 已達 Webhook 綁定上限 (10/10)。如需新增，請先移除舊的標籤。"
            }
            return
        }

        val existingTag = webhookRepository.findByClientAndTag(client.clientId, tag)
        if (existingTag != null) {
            interaction.respondEphemeral {
                content = "❌ 標籤 `$tag` 已存在，同一 Client 標籤不可重複。"
            }
            return
        }

        val customUrl = strings["url"]?.trim()?.ifBlank { null }
        val rawGuildId = interaction.data.guildId.value
        val guildId = rawGuildId?.toString().orEmpty()
        val channelId = interaction.channelId.toString()

        if (customUrl == null) {
            val member = rawGuildId?.let { interaction.user.asMemberOrNull(it) }
            val channelPermissions =
                runCatching {
                    (interaction.channel.asChannelOrNull() as? TopGuildChannel)?.getEffectivePermissions(interaction.user.id)
                }.getOrNull()
            val permissions = channelPermissions ?: member?.getPermissions()
            val hasManageWebhooks =
                permissions?.contains(Permission.ManageWebhooks) == true ||
                    permissions?.contains(Permission.Administrator) == true

            if (!hasManageWebhooks) {
                interaction.respondEphemeral {
                    content = "❌ 您在此頻道沒有「管理 Webhook」權限，無法自動建立 Webhook。"
                }
                return
            }

            val created =
                try {
                    interaction.kord.rest.webhook.createWebhook(
                        channelId = interaction.channelId,
                        name = tag,
                    ) {}
                } catch (e: Exception) {
                    interaction.respondEphemeral {
                        content = "❌ 自動建立 Discord Webhook 失敗：${e.message}"
                    }
                    return
                }

            val token = created.token.value.orEmpty()
            val webhookUrl = "https://discord.com/api/webhooks/${created.id}/$token"
            val now = System.currentTimeMillis()

            val record =
                McpWebhookRecord(
                    id = UUID.randomUUID().toString(),
                    clientId = client.clientId,
                    tag = tag,
                    guildId = guildId,
                    channelId = channelId,
                    webhookUrl = webhookUrl,
                    isAutoProvisioned = true,
                    createdAt = now,
                )
            webhookRepository.addWebhook(record)

            interaction.respondEphemeral {
                content = "✅ 成功為頻道 <#$channelId> 自動建立並綁定 Webhook！標籤：`$tag`。"
            }
        } else {
            if (!isValidWebhookUrl(customUrl)) {
                interaction.respondEphemeral {
                    content = "❌ 無效的 Discord Webhook URL。URL 必須以 https://discord.com/api/webhooks/ 或 https://canary.discord.com/api/webhooks/ 開頭。"
                }
                return
            }

            val now = System.currentTimeMillis()
            val record =
                McpWebhookRecord(
                    id = UUID.randomUUID().toString(),
                    clientId = client.clientId,
                    tag = tag,
                    guildId = guildId,
                    channelId = channelId,
                    webhookUrl = customUrl,
                    isAutoProvisioned = false,
                    createdAt = now,
                )
            webhookRepository.addWebhook(record)

            interaction.respondEphemeral {
                content = "✅ 成功綁定自訂 Webhook！標籤：`$tag`。"
            }
        }
    }

    private suspend fun handleWebhookRemove(
        interaction: ChatInputCommandInteraction,
        strings: Map<String, String>,
    ) {
        val userId = interaction.user.id.toString()
        val client = clientRepository.findByUserId(userId)
        if (client == null) {
            interaction.respondEphemeral {
                content = "❌ 您尚未建立 Client ID，請先執行 `/mcp client create` 建立金鑰。"
            }
            return
        }

        val tag = strings["tag"]?.trim().orEmpty()
        val record = webhookRepository.findByClientAndTag(client.clientId, tag)
        if (record == null) {
            interaction.respondEphemeral {
                content = "❌ 找不到標籤為 `$tag` 的 Webhook。"
            }
            return
        }

        if (record.isAutoProvisioned) {
            deleteDiscordWebhookSafely(
                kord = interaction.kord,
                webhookUrl = record.webhookUrl,
                reason = "Deleted via /mcp webhook remove",
                component = "McpWebhookCommand",
            )
        }

        webhookRepository.removeByTag(client.clientId, tag)

        interaction.respondEphemeral {
            content = "✅ 已成功移除標籤為 `$tag` 的 Webhook。"
        }
    }

    private suspend fun handleWebhookList(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val client = clientRepository.findByUserId(userId)
        if (client == null) {
            interaction.respondEphemeral {
                content = "❌ 您尚未建立 Client ID，請先執行 `/mcp client create` 建立金鑰。"
            }
            return
        }

        val webhooks = webhookRepository.listByClientId(client.clientId)
        if (webhooks.isEmpty()) {
            interaction.respondEphemeral {
                content = "ℹ️ 您目前尚未綁定任何 Webhook。\n請使用 `/mcp webhook add tag:<tag> [url:<url>]` 來新增或自動建立 Webhook。"
            }
            return
        }

        val count = webhooks.size
        val message =
            buildString {
                appendLine("📋 **MCP Webhook 列表** (`$count/$MAX_WEBHOOKS_PER_CLIENT`)")
                appendLine()
                for (wh in webhooks) {
                    val epochSeconds = wh.createdAt / 1000
                    val instant = Instant.ofEpochMilli(wh.createdAt)
                    val formattedDate = DateTimeFormatter.ISO_INSTANT.format(instant)
                    appendLine("- **標籤**: `${wh.tag}`")
                    if (wh.isAutoProvisioned) {
                        appendLine("  - **頻道**: <#${wh.channelId}> (`${wh.channelId}`)")
                    }
                    appendLine("  - **URL**: `${wh.webhookUrl}`")
                    appendLine("  - **建立時間**: <t:$epochSeconds:F> ($formattedDate)")
                    appendLine("  - **自動建立**: ${if (wh.isAutoProvisioned) "是" else "否"}")
                    appendLine()
                }
                append("- **Webhook 配額**: `$count/$MAX_WEBHOOKS_PER_CLIENT`")
            }

        interaction.respondEphemeral {
            content = message.trimEnd()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(McpWebhookCommand::class.java)
        const val MAX_WEBHOOKS_PER_CLIENT = 10
        val TAG_REGEX = Regex("^[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+$")
        val WEBHOOK_URL_REGEX = Regex("""https://(?:canary\.)?discord\.com/api/webhooks/([^/]+)/([^/]+)""")

        suspend fun deleteDiscordWebhookSafely(
            kord: Kord,
            webhookUrl: String,
            reason: String,
            component: String = "McpWebhookCommand",
        ) {
            val match = WEBHOOK_URL_REGEX.find(webhookUrl)
            if (match == null) {
                logger.warn("Webhook has unparseable URL: {}", webhookUrl)
                return
            }
            val rawId = match.groupValues[1]
            val token = match.groupValues[2]
            val id =
                rawId.toLongOrNull()?.let { Snowflake(it) } ?: run {
                    logger.warn("Webhook has invalid Snowflake ID: {}", rawId)
                    return
                }

            try {
                kord.rest.webhook.deleteWebhook(id, token, reason = reason)
            } catch (e: Exception) {
                JsonLogger.log(
                    layer = "COMMAND",
                    component = component,
                    operation = "deleteDiscordWebhook",
                    data =
                        mapOf(
                            "webhookId" to id.toString(),
                            "cause" to (e.message ?: "Unknown error"),
                        ),
                    status = "DEGRADED",
                )
                logger.warn("Failed to delete Discord channel webhook {}: {}", id, e.message)
            }
        }

        fun isValidWebhookUrl(url: String): Boolean {
            val prefix =
                when {
                    url.startsWith("https://discord.com/api/webhooks/") -> "https://discord.com/api/webhooks/"
                    url.startsWith("https://canary.discord.com/api/webhooks/") -> "https://canary.discord.com/api/webhooks/"
                    else -> return false
                }
            return url.length > prefix.length && url.substring(prefix.length).isNotBlank()
        }
    }
}
