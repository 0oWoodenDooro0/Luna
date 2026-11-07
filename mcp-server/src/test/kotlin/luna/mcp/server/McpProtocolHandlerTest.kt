package luna.mcp.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.mcp.data.McpWebhookRecord
import luna.mcp.data.McpWebhookRepository
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpProtocolHandlerTest {
    private lateinit var database: Database
    private lateinit var repository: McpWebhookRepository
    private lateinit var broadcaster: DiscordWebhookBroadcaster
    private lateinit var handler: McpProtocolHandler

    @BeforeTest
    fun setUp() {
        val dbName = "test_protocol_${UUID.randomUUID()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        repository = McpWebhookRepository(database)
        val client =
            HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) }) {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json()
                }
            }
        broadcaster = DiscordWebhookBroadcaster(repository, client)
        handler = McpProtocolHandler(broadcaster)
    }

    @Test
    fun `handles initialize request and returns serverInfo and capabilities`() =
        runTest {
            val requestJson =
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}"""
            val response = handler.handle(requestJson, clientId = "test-client")
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            assertEquals("2.0", root["jsonrpc"]?.jsonPrimitive?.content)
            assertEquals(1, root["id"]?.jsonPrimitive?.content?.toInt())

            val result = root["result"]?.jsonObject
            assertNotNull(result)
            assertEquals("2024-11-05", result["protocolVersion"]?.jsonPrimitive?.content)
            assertEquals(
                "luna-mcp-server",
                result["serverInfo"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "1.0.0",
                result["serverInfo"]
                    ?.jsonObject
                    ?.get("version")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertNotNull(result["capabilities"]?.jsonObject?.get("tools"))
        }

    @Test
    fun `handles notifications initialized without returning a response`() =
        runTest {
            val notificationJson =
                """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
            val response = handler.handle(notificationJson, clientId = "test-client")
            assertNull(response)
        }

    @Test
    fun `handles tools list request returning send_discord_message and list_targets specification`() =
        runTest {
            val requestJson =
                """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
            val response = handler.handle(requestJson, clientId = "test-client")
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val tools = root["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools)
            assertEquals(2, tools.size)

            val sendMessageTool = tools.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "send_discord_message" }?.jsonObject
            assertNotNull(sendMessageTool)
            assertEquals(
                "Send a message to registered Discord channels via webhooks. Specify 'target' with a specific tag name or 'all' to broadcast to all channels.",
                sendMessageTool["description"]?.jsonPrimitive?.content,
            )
            val sendSchema = sendMessageTool["inputSchema"]?.jsonObject
            assertNotNull(sendSchema)
            assertEquals("object", sendSchema["type"]?.jsonPrimitive?.content)
            val sendRequired = sendSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertNotNull(sendRequired)
            assertTrue(sendRequired.contains("content"))
            assertTrue(sendRequired.contains("target"))
            assertNotNull(sendSchema["properties"]?.jsonObject?.get("content"))
            assertNotNull(sendSchema["properties"]?.jsonObject?.get("target"))

            val listTargetsTool = tools.firstOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == "list_targets" }?.jsonObject
            assertNotNull(listTargetsTool)
            assertEquals(
                "List all registered Discord channel tags and quota status for the authenticated client",
                listTargetsTool["description"]?.jsonPrimitive?.content,
            )
            val listSchema = listTargetsTool["inputSchema"]?.jsonObject
            assertNotNull(listSchema)
            assertEquals("object", listSchema["type"]?.jsonPrimitive?.content)
            assertNotNull(listSchema["properties"]?.jsonObject)
        }

    @Test
    fun `handles list_targets returning client tags and quota status`() =
        runTest {
            repository.addWebhook(
                McpWebhookRecord("wh-client-1", "client-alpha", "general", "g-1", "c-1", "https://discord.com/api/webhooks/111/alpha1", createdAt = 1000L),
            )
            repository.addWebhook(
                McpWebhookRecord("wh-client-2", "client-alpha", "alerts", "g-1", "c-2", "https://discord.com/api/webhooks/111/alpha2", createdAt = 2000L),
            )
            repository.addWebhook(
                McpWebhookRecord("wh-other", "client-beta", "other", "g-2", "c-3", "https://discord.com/api/webhooks/222/beta", createdAt = 3000L),
            )

            val requestJson =
                """{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"list_targets","arguments":{}}}"""
            val response = handler.handle(requestJson, clientId = "client-alpha")
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val result = root["result"]?.jsonObject
            assertNotNull(result)
            assertEquals(false, result["isError"]?.jsonPrimitive?.booleanOrNull)

            val text =
                result["content"]
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            val payload = Json.parseToJsonElement(text).jsonObject
            val tags = payload["tags"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("general", "alerts"), tags)
            assertEquals(2, payload["count"]?.jsonPrimitive?.content?.toInt())
            assertEquals(10, payload["maxQuota"]?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun `send_discord_message with target tag only delivers to matching tag`() =
        runTest {
            val dispatchedUrls = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { req ->
                        dispatchedUrls.add(req.url.toString())
                        respond("{}", HttpStatusCode.NoContent)
                    },
                ) {
                    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
                }
            val broadcasterWithCapture = DiscordWebhookBroadcaster(repository, client)
            val customHandler = McpProtocolHandler(broadcasterWithCapture, repository)

            repository.addWebhook(
                McpWebhookRecord("wh-1", "client-1", "alerts", "g-1", "c-1", "https://discord.com/api/webhooks/111/alerts", createdAt = 1000L),
            )
            repository.addWebhook(
                McpWebhookRecord("wh-2", "client-1", "general", "g-1", "c-2", "https://discord.com/api/webhooks/111/general", createdAt = 2000L),
            )

            val requestJson =
                """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"content":"Deploy alert","target":"alerts"}}}"""
            val response = customHandler.handle(requestJson, clientId = "client-1")
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val result = root["result"]?.jsonObject
            assertNotNull(result)
            assertEquals(false, result["isError"]?.jsonPrimitive?.booleanOrNull)

            val text =
                result["content"]
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertEquals("✅ 已成功將訊息發送至 1 個頻道 [alerts]", text)
            assertEquals(listOf("https://discord.com/api/webhooks/111/alerts"), dispatchedUrls)
        }

    @Test
    fun `send_discord_message with target all delivers to all client webhooks`() =
        runTest {
            val dispatchedUrls = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { req ->
                        dispatchedUrls.add(req.url.toString())
                        respond("{}", HttpStatusCode.NoContent)
                    },
                ) {
                    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
                }
            val broadcasterWithCapture = DiscordWebhookBroadcaster(repository, client)
            val customHandler = McpProtocolHandler(broadcasterWithCapture, repository)

            repository.addWebhook(
                McpWebhookRecord("wh-1", "client-1", "general", "g-1", "c-1", "https://discord.com/api/webhooks/111/general", createdAt = 1000L),
            )
            repository.addWebhook(
                McpWebhookRecord("wh-2", "client-1", "alerts", "g-1", "c-2", "https://discord.com/api/webhooks/111/alerts", createdAt = 2000L),
            )

            val requestJson =
                """{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"content":"Broadcast to all","target":"all"}}}"""
            val response = customHandler.handle(requestJson, clientId = "client-1")
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val result = root["result"]?.jsonObject
            assertNotNull(result)
            assertEquals(false, result["isError"]?.jsonPrimitive?.booleanOrNull)

            val text =
                result["content"]
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertEquals("✅ 已成功將訊息發送至 2 個頻道 [general, alerts]", text)
            assertEquals(2, dispatchedUrls.size)
            assertTrue(dispatchedUrls.contains("https://discord.com/api/webhooks/111/general"))
            assertTrue(dispatchedUrls.contains("https://discord.com/api/webhooks/111/alerts"))
        }

    @Test
    fun `send_discord_message with unknown tag returns Traditional Chinese guidance error`() =
        runTest {
            repository.addWebhook(
                McpWebhookRecord("wh-1", "client-1", "general", "g-1", "c-1", "https://discord.com/api/webhooks/111/general", createdAt = 1000L),
            )

            val requestJson =
                """{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"content":"Hello","target":"nonexistent"}}}"""
            val response = handler.handle(requestJson, clientId = "client-1")
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val result = root["result"]?.jsonObject
            assertNotNull(result)
            assertEquals(true, result["isError"]?.jsonPrimitive?.booleanOrNull)

            val text =
                result["content"]
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertEquals("❌ 發送失敗：找不到標籤為 'nonexistent' 的 Webhook。請先在 Discord 使用 /mcp webhook add tag:nonexistent 綁定頻道，或調用 list_targets 查看現有可用標籤。", text)
        }

    @Test
    fun `send_discord_message with empty webhooks returns Traditional Chinese guidance error`() =
        runTest {
            val requestJson =
                """{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"content":"Hello","target":"general"}}}"""
            val response = handler.handle(requestJson, clientId = "client-empty")
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val result = root["result"]?.jsonObject
            assertNotNull(result)
            assertEquals(true, result["isError"]?.jsonPrimitive?.booleanOrNull)

            val text =
                result["content"]
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertEquals("❌ 發送失敗：尚未綁定任何 Discord 頻道 Webhook。請在 Discord 中執行 /mcp webhook add tag:<標籤> 進行綁定。", text)
        }

    @Test
    fun `multi-tenant isolation Client A cannot see or deliver to Client B webhooks with same tag`() =
        runTest {
            val dispatchedUrls = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { req ->
                        dispatchedUrls.add(req.url.toString())
                        respond("{}", HttpStatusCode.NoContent)
                    },
                ) {
                    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) { json() }
                }
            val broadcasterWithCapture = DiscordWebhookBroadcaster(repository, client)
            val customHandler = McpProtocolHandler(broadcasterWithCapture, repository)

            repository.addWebhook(
                McpWebhookRecord("wh-a", "client-A", "production", "guild-A", "chan-A", "https://discord.com/api/webhooks/AAA/production", createdAt = 1000L),
            )
            repository.addWebhook(
                McpWebhookRecord("wh-b", "client-B", "production", "guild-B", "chan-B", "https://discord.com/api/webhooks/BBB/production", createdAt = 2000L),
            )

            // Client A calls send_discord_message for "production"
            val requestJson =
                """{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"content":"Hello from A","target":"production"}}}"""
            val responseA = customHandler.handle(requestJson, clientId = "client-A")
            assertNotNull(responseA)

            val rootA = Json.parseToJsonElement(responseA).jsonObject
            assertEquals(
                false,
                rootA["result"]
                    ?.jsonObject
                    ?.get("isError")
                    ?.jsonPrimitive
                    ?.booleanOrNull,
            )
            assertEquals(1, dispatchedUrls.size)
            assertEquals("https://discord.com/api/webhooks/AAA/production", dispatchedUrls[0])

            // Verify Client A list_targets only sees Client A's webhooks
            val listReq = """{"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"list_targets","arguments":{}}}"""
            val listRespA = customHandler.handle(listReq, clientId = "client-A")
            assertNotNull(listRespA)
            val listResultA = Json.parseToJsonElement(listRespA).jsonObject["result"]?.jsonObject
            val listTextA =
                listResultA
                    ?.get("content")
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            val listJsonA = Json.parseToJsonElement(listTextA).jsonObject
            assertEquals(1, listJsonA["count"]?.jsonPrimitive?.content?.toInt())
            assertEquals(listOf("production"), listJsonA["tags"]?.jsonArray?.map { it.jsonPrimitive.content })
        }

    @Test
    fun `returns json-rpc error when unknown tool is requested`() =
        runTest {
            val requestJson =
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"unknown_tool","arguments":{}}}"""
            val response = handler.handle(requestJson, clientId = "test-client")
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val error = root["error"]?.jsonObject
            assertNotNull(error)
            assertEquals(-32601, error["code"]?.jsonPrimitive?.content?.toInt())
        }
}
