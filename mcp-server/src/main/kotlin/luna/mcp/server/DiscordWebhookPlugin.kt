package luna.mcp.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import luna.mcp.data.McpWebhookRepository

/**
 * Built-in [McpServerPlugin] exposing Discord webhook broadcast and target listing tools.
 */
class DiscordWebhookPlugin(
    private val broadcaster: DiscordWebhookBroadcaster,
    private val repository: McpWebhookRepository = broadcaster.repository,
) : McpServerPlugin {
    override val name: String = "discord-webhook"

    private val sendDiscordMessageTool =
        object : McpTool {
            override val name: String = TOOL_SEND_MESSAGE
            override val description: String =
                "Send a message to registered Discord channels via webhooks. Specify 'target' with a specific tag name or 'all' to broadcast to all channels."
            override val inputSchema: JsonObject =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") {
                            put("type", "string")
                            put("description", "The message text to send to Discord")
                        }
                        putJsonObject("target") {
                            put("type", "string")
                            put(
                                "description",
                                "The target Discord channel tag name, or 'all' to send to all registered channels",
                            )
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("content"))
                        add(JsonPrimitive("target"))
                    }
                }

            override suspend fun execute(
                arguments: JsonObject?,
                auth: McpAuthContext,
            ): McpToolResult = executeSendDiscordMessage(arguments, auth.clientId)
        }

    private val listTargetsTool =
        object : McpTool {
            override val name: String = TOOL_LIST_TARGETS
            override val description: String =
                "List all registered Discord channel tags and quota status for the authenticated client"
            override val inputSchema: JsonObject =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                }

            override suspend fun execute(
                arguments: JsonObject?,
                auth: McpAuthContext,
            ): McpToolResult = executeListTargets(auth.clientId)
        }

    override fun tools(): List<McpTool> = listOf(sendDiscordMessageTool, listTargetsTool)

    private fun executeListTargets(clientId: String): McpToolResult {
        val webhooks = repository.listByClientId(clientId)
        val tags = webhooks.map { it.tag }
        val payload =
            buildJsonObject {
                putJsonArray("tags") {
                    for (tag in tags) {
                        add(JsonPrimitive(tag))
                    }
                }
                put("quota", "${tags.size}/$MAX_WEBHOOK_QUOTA")
                put("count", tags.size)
                put("maxQuota", MAX_WEBHOOK_QUOTA)
            }
        return McpToolResult.Text(text = payload.toString(), isError = false)
    }

    private suspend fun executeSendDiscordMessage(
        arguments: JsonObject?,
        clientId: String,
    ): McpToolResult {
        val content = arguments?.get("content")?.jsonPrimitive?.content
        val target = arguments?.get("target")?.jsonPrimitive?.content

        if (content.isNullOrBlank() || target.isNullOrBlank()) {
            return McpToolResult.RpcError(
                code = -32602,
                message = "Missing or blank required argument 'content' or 'target'",
            )
        }

        val webhooks = repository.listByClientId(clientId)
        if (webhooks.isEmpty()) {
            return McpToolResult.Text(
                text = "❌ 發送失敗：尚未綁定任何 Discord 頻道 Webhook。請在 Discord 中執行 /mcp webhook add tag:<標籤> 進行綁定。",
                isError = true,
            )
        }

        val matchedWebhooks =
            if (target == "all") {
                webhooks
            } else {
                webhooks.filter { it.tag == target }
            }

        if (matchedWebhooks.isEmpty()) {
            return McpToolResult.Text(
                text = "❌ 發送失敗：找不到標籤為 '$target' 的 Webhook。請先在 Discord 使用 /mcp webhook add tag:$target 綁定頻道，或調用 list_targets 查看現有可用標籤。",
                isError = true,
            )
        }

        broadcaster.broadcastToWebhooks(matchedWebhooks, content)

        val successText = "✅ 已成功將訊息發送至 ${matchedWebhooks.size} 個頻道 [${matchedWebhooks.joinToString { it.tag }}]"
        return McpToolResult.Text(text = successText, isError = false)
    }

    companion object {
        const val TOOL_SEND_MESSAGE = McpProtocolHandler.TOOL_SEND_MESSAGE
        const val TOOL_LIST_TARGETS = McpProtocolHandler.TOOL_LIST_TARGETS
        const val MAX_WEBHOOK_QUOTA = McpProtocolHandler.MAX_WEBHOOK_QUOTA
    }
}
