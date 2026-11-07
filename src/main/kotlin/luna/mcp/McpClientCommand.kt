package luna.mcp

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.respondEphemeral
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.GroupCommand
import dev.kord.rest.builder.interaction.group
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.subCommand
import luna.core.Command
import java.time.Instant
import java.time.format.DateTimeFormatter

class McpClientCommand(
    private val clientRepository: McpClientRepository,
    private val webhookRepository: McpWebhookRepository,
    private val publicBaseUrl: String = DEFAULT_PUBLIC_BASE_URL,
) : Command {
    override val name: String = "mcp"
    override val description: String = "Manage Model Context Protocol (MCP) clients and webhooks"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            group("client", "Manage personal MCP client credentials") {
                subCommand("create", "Create a personal MCP client ID and secret") {
                    string("name", "Custom name for your client (optional)") {
                        required = false
                    }
                }
                subCommand("show", "Show your current MCP client ID and webhook quota usage") {}
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val groupCommand = interaction.command as? GroupCommand
        if (groupCommand == null || groupCommand.groupName != "client") {
            interaction.respondEphemeral {
                content = "Unknown command. Please use `/mcp client create` or `/mcp client show`."
            }
            return
        }

        when (groupCommand.name) {
            "create" -> {
                handleClientCreate(interaction, groupCommand)
            }

            "show" -> {
                handleClientShow(interaction)
            }

            else -> {
                interaction.respondEphemeral {
                    content = "Unknown subcommand. Please use `/mcp client create` or `/mcp client show`."
                }
            }
        }
    }

    private suspend fun handleClientCreate(
        interaction: ChatInputCommandInteraction,
        groupCommand: GroupCommand,
    ) {
        val userId = interaction.user.id.toString()
        val existing = clientRepository.findByUserId(userId)
        if (existing != null) {
            interaction.respondEphemeral {
                content =
                    "❌ 您已經擁有一組 Client ID：`${existing.clientId}`。\n" +
                    "每個 Discord 帳號限制僅能建立 1 組 MCP Client。\n" +
                    "若要查看詳細資訊或配額，請使用 `/mcp client show`。\n" +
                    "若密鑰遺失或外洩，請使用 `/mcp client reset-secret`。"
            }
            return
        }

        val clientId = McpSecurity.generateClientId()
        val plainSecret = McpSecurity.generateClientSecret()
        val salt = McpSecurity.generateSalt()
        val secretHash = McpSecurity.hashSecret(plainSecret, salt)

        val clientName = groupCommand.strings["name"]?.trim()?.ifBlank { null } ?: "Personal Gemini Client"
        val now = System.currentTimeMillis()

        val record =
            McpClientRecord(
                clientId = clientId,
                userId = userId,
                clientName = clientName,
                secretHash = secretHash,
                secretSalt = salt,
                createdAt = now,
                updatedAt = now,
            )
        clientRepository.createClient(record)

        val normalizedBaseUrl = publicBaseUrl.trimEnd('/')
        val sseUrl = "$normalizedBaseUrl/mcp/sse"
        val authUrl = "$normalizedBaseUrl/oauth/authorize"
        val tokenUrl = "$normalizedBaseUrl/oauth/token"

        val message =
            buildString {
                appendLine("✅ **MCP Client 建立成功！**")
                appendLine()
                appendLine("- **Client ID**: `$clientId`")
                appendLine("- **Client Secret**: `$plainSecret`")
                appendLine()
                appendLine("⚠️ 密鑰僅會顯示這一次，請妥善保存，切勿分享他人！")
                appendLine()
                appendLine("**Gemini 串接設定資訊**：")
                appendLine("- **Server SSE URL**: `$sseUrl`")
                appendLine("- **Client ID**: `$clientId`")
                appendLine("- **Client Secret**: `$plainSecret`")
                appendLine("- **Authorization URL**: `$authUrl`")
                appendLine("- **Token URL**: `$tokenUrl`")
            }

        interaction.respondEphemeral {
            content = message.trimEnd()
        }
    }

    private suspend fun handleClientShow(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val client = clientRepository.findByUserId(userId)
        if (client == null) {
            interaction.respondEphemeral {
                content =
                    "ℹ️ 您尚未建立 MCP Client。\n" +
                    "請執行 `/mcp client create [name]` 建立您的 Client ID 與密鑰以連接 Gemini。"
            }
            return
        }

        val webhooks = webhookRepository.listByClientId(client.clientId)
        val quotaUsage = "${webhooks.size}/10"
        val epochSeconds = client.createdAt / 1000
        val instant = Instant.ofEpochMilli(client.createdAt)
        val formattedDate = DateTimeFormatter.ISO_INSTANT.format(instant)

        val message =
            buildString {
                appendLine("📋 **MCP Client 資訊**")
                appendLine()
                appendLine("- **Client ID**: `${client.clientId}`")
                appendLine("- **名稱**: `${client.clientName}`")
                appendLine("- **建立時間**: <t:$epochSeconds:F> ($formattedDate)")
                appendLine("- **Webhook 配額**: `$quotaUsage`")
            }

        interaction.respondEphemeral {
            content = message.trimEnd()
        }
    }

    companion object {
        const val DEFAULT_PUBLIC_BASE_URL = "https://woodendoor.website"
    }
}
