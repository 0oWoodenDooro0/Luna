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
import luna.mcp.data.McpWebhookRepository

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
    val plugins: List<McpServerPlugin>,
    private val json: Json = DEFAULT_JSON,
) {
    constructor(
        broadcaster: DiscordWebhookBroadcaster,
        repository: McpWebhookRepository = broadcaster.repository,
        plugins: List<McpServerPlugin> = listOf(DiscordWebhookPlugin(broadcaster, repository)),
        json: Json = DEFAULT_JSON,
    ) : this(plugins = plugins, json = json)

    suspend fun handle(
        requestBody: String,
        clientId: String,
    ): String? = handle(requestBody, McpAuthContext(clientId = clientId))

    suspend fun handle(
        requestBody: String,
        auth: McpAuthContext,
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

        val response = handleRequest(request, auth) ?: return null
        return json.encodeToString(JsonRpcResponse.serializer(), response)
    }

    suspend fun handleRequest(
        request: JsonRpcRequest,
        clientId: String,
    ): JsonRpcResponse? = handleRequest(request, McpAuthContext(clientId = clientId))

    suspend fun handleRequest(
        request: JsonRpcRequest,
        auth: McpAuthContext,
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
                handleToolsList(request, auth)
            }

            "tools/call" -> {
                handleToolsCall(request, auth)
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

    private fun visibleTools(auth: McpAuthContext): List<McpTool> =
        plugins
            .filter { it.isEnabledFor(auth) }
            .flatMap { it.tools() }
            .filter { it.isVisibleTo(auth) }

    private fun handleToolsList(
        request: JsonRpcRequest,
        auth: McpAuthContext,
    ): JsonRpcResponse {
        val result =
            buildJsonObject {
                putJsonArray("tools") {
                    for (tool in visibleTools(auth)) {
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("inputSchema", tool.inputSchema)
                            },
                        )
                    }
                }
            }
        return JsonRpcResponse(id = request.id, result = result)
    }

    private suspend fun handleToolsCall(
        request: JsonRpcRequest,
        auth: McpAuthContext,
    ): JsonRpcResponse {
        val toolName =
            request.params
                ?.get("name")
                ?.jsonPrimitive
                ?.content

        val tool = visibleTools(auth).firstOrNull { it.name == toolName }
        if (tool == null) {
            return JsonRpcResponse(
                id = request.id,
                error = JsonRpcError(code = -32601, message = "Unknown tool: $toolName"),
            )
        }

        val arguments = request.params?.get("arguments")?.jsonObject
        return when (val outcome = tool.execute(arguments, auth)) {
            is McpToolResult.Text -> {
                toolResult(request.id, outcome.text, outcome.isError)
            }

            is McpToolResult.RpcError -> {
                JsonRpcResponse(
                    id = request.id,
                    error = JsonRpcError(code = outcome.code, message = outcome.message),
                )
            }
        }
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

        val DEFAULT_JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}
