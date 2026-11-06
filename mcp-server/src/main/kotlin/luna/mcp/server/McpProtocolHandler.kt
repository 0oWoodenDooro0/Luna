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
    private val broadcaster: DiscordWebhookBroadcaster,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
) {
    suspend fun handle(requestBody: String): String? {
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

        val response = handleRequest(request) ?: return null
        return json.encodeToString(JsonRpcResponse.serializer(), response)
    }

    suspend fun handleRequest(request: JsonRpcRequest): JsonRpcResponse? =
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
                handleToolsCall(request)
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
                            put("name", TOOL_NAME)
                            put("description", "Send a message to all registered Discord channels via webhooks")
                            putJsonObject("inputSchema") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("message") {
                                        put("type", "string")
                                        put("description", "The message text to send to Discord")
                                    }
                                }
                                putJsonArray("required") {
                                    add(kotlinx.serialization.json.JsonPrimitive("message"))
                                }
                            }
                        },
                    )
                }
            }
        return JsonRpcResponse(id = request.id, result = result)
    }

    private suspend fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        val toolName =
            request.params
                ?.get("name")
                ?.jsonPrimitive
                ?.content
        if (toolName != TOOL_NAME) {
            return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(code = -32601, message = "Unknown tool: $toolName"),
            )
        }

        val arguments = request.params["arguments"]?.jsonObject
        val message = arguments?.get("message")?.jsonPrimitive?.content
        if (message == null) {
            return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(code = -32602, message = "Missing required argument 'message'"),
            )
        }

        val broadcastResult = broadcaster.broadcast(message)
        val result =
            buildJsonObject {
                putJsonArray("content") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", broadcastResult.toJsonString())
                        },
                    )
                }
                put("isError", false)
            }

        return JsonRpcResponse(id = request.id, result = result)
    }

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_NAME = "luna-mcp-server"
        const val SERVER_VERSION = "1.0.0"
        const val TOOL_NAME = "send_discord_message"
    }
}
