package luna.mcp.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

class McpProtocolHandler(
    val broadcaster: DiscordWebhookBroadcaster,
    val repository: McpWebhookRepository = broadcaster.repository,
    private val defaultClientId: String = DEFAULT_CLIENT_ID,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
) {
    suspend fun handle(
        requestBody: String,
        clientId: String = defaultClientId,
    ): String? {
        val request =
            runCatching {
                json.decodeFromString<JsonRpcRequest>(requestBody)
            }.getOrElse {
                val errorResponse =
                    JsonRpcResponse(
                        id = null,
                        error = JsonRpcError(code = -32700, message = "Parse error: ${it.message}"),
                    )
                return json.encodeToString(JsonRpcResponse.serializer(), errorResponse)
            }

        val response = handleRequest(request, clientId) ?: return null
        return json.encodeToString(JsonRpcResponse.serializer(), response)
    }

    suspend fun handleRequest(
        request: JsonRpcRequest,
        clientId: String = defaultClientId,
    ): JsonRpcResponse? =
        when (request.method) {
            "initialize" -> {
                handleInitialize(request)
            }

            "notifications/initialized" -> {
                null
            }

            "ping" -> {
                JsonRpcResponse(id = request.id, result = buildJsonObject {})
            }

            "tools/list" -> {
                handleToolsList(request)
            }

            "tools/call" -> {
                handleToolsCall(request, clientId)
            }

            else -> {
                if (request.id == null) {
                    null
                } else {
                    JsonRpcResponse(
                        id = request.id,
                        error = JsonRpcError(code = -32601, message = "Method not found: ${request.method}"),
                    )
                }
            }
        }

    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        val result =
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                putJsonObject("capabilities") {
                    putJsonObject("tools") {}
                }
                putJsonObject("serverInfo") {
                    put("name", SERVER_NAME)
                    put("version", SERVER_VERSION)
                }
            }
        return JsonRpcResponse(id = request.id, result = result)
    }

    private fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        val result =
            buildJsonObject {
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            put("name", TOOL_SEND_MESSAGE)
                            put(
                                "description",
                                "Send a message to registered Discord channels via webhooks. Specify 'target' with a specific tag name or 'all' to broadcast to all channels.",
                            )
                            putJsonObject("inputSchema") {
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
                                    add(kotlinx.serialization.json.JsonPrimitive("content"))
                                    add(kotlinx.serialization.json.JsonPrimitive("target"))
                                }
                            }
                        },
                    )
                    add(
                        buildJsonObject {
                            put("name", TOOL_LIST_TARGETS)
                            put(
                                "description",
                                "List all registered Discord channel tags and quota status for the authenticated client",
                            )
                            putJsonObject("inputSchema") {
                                put("type", "object")
                                putJsonObject("properties") {}
                            }
                        },
                    )
                }
            }
        return JsonRpcResponse(id = request.id, result = result)
    }

    private suspend fun handleToolsCall(
        request: JsonRpcRequest,
        clientId: String,
    ): JsonRpcResponse {
        val toolName =
            request.params
                ?.get("name")
                ?.jsonPrimitive
                ?.content

        return when (toolName) {
            TOOL_LIST_TARGETS -> {
                handleListTargets(request, clientId)
            }

            TOOL_SEND_MESSAGE -> {
                handleSendDiscordMessage(request, clientId)
            }

            else -> {
                JsonRpcResponse(
                    id = request.id,
                    error = JsonRpcError(code = -32601, message = "Unknown tool: $toolName"),
                )
            }
        }
    }

    private fun handleListTargets(
        request: JsonRpcRequest,
        clientId: String,
    ): JsonRpcResponse {
        val webhooks = repository.listByClientId(clientId)
        val tags = webhooks.map { it.tag }
        val payload =
            buildJsonObject {
                putJsonArray("tags") {
                    for (tag in tags) {
                        add(kotlinx.serialization.json.JsonPrimitive(tag))
                    }
                }
                put("quota", "${tags.size}/$MAX_WEBHOOK_QUOTA")
                put("count", tags.size)
                put("maxQuota", MAX_WEBHOOK_QUOTA)
            }

        return toolResult(request.id, payload.toString(), isError = false)
    }

    private suspend fun handleSendDiscordMessage(
        request: JsonRpcRequest,
        clientId: String,
    ): JsonRpcResponse {
        val arguments = request.params?.get("arguments")?.jsonObject
        val content = arguments?.get("content")?.jsonPrimitive?.content
        val target = arguments?.get("target")?.jsonPrimitive?.content

        if (content.isNullOrBlank() || target.isNullOrBlank()) {
            return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(code = -32602, message = "Missing or blank required argument 'content' or 'target'"),
            )
        }

        val webhooks = repository.listByClientId(clientId)
        if (webhooks.isEmpty()) {
            return toolResult(
                id = request.id,
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
            return toolResult(
                id = request.id,
                text = "❌ 發送失敗：找不到標籤為 '$target' 的 Webhook。請先在 Discord 使用 /mcp webhook add tag:$target 綁定頻道，或調用 list_targets 查看現有可用標籤。",
                isError = true,
            )
        }

        broadcaster.broadcastToWebhooks(matchedWebhooks, content)

        val successText = "✅ 已成功將訊息發送至 ${matchedWebhooks.size} 個頻道 [${matchedWebhooks.joinToString { it.tag }}]"
        return toolResult(
            id = request.id,
            text = successText,
            isError = false,
        )
    }

    private fun toolResult(
        id: JsonElement?,
        text: String,
        isError: Boolean,
    ): JsonRpcResponse {
        val result =
            buildJsonObject {
                putJsonArray("content") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                }
                put("isError", isError)
            }
        return JsonRpcResponse(id = id, result = result)
    }

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_NAME = "luna-mcp-server"
        const val SERVER_VERSION = "1.0.0"
        const val TOOL_NAME = "send_discord_message"
        const val TOOL_SEND_MESSAGE = "send_discord_message"
        const val TOOL_LIST_TARGETS = "list_targets"
        const val MAX_WEBHOOK_QUOTA = 10
        const val DEFAULT_CLIENT_ID = "luna-mcp-client"
    }
}
