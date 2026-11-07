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
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
            val response = handler.handle(requestJson)
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
            val response = handler.handle(notificationJson)
            assertNull(response)
        }

    @Test
    fun `handles tools list request returning send_discord_message specification`() =
        runTest {
            val requestJson =
                """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
            val response = handler.handle(requestJson)
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val tools = root["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools)
            assertEquals(1, tools.size)

            val tool = tools[0].jsonObject
            assertEquals("send_discord_message", tool["name"]?.jsonPrimitive?.content)
            val inputSchema = tool["inputSchema"]?.jsonObject
            assertNotNull(inputSchema)
            assertEquals("object", inputSchema["type"]?.jsonPrimitive?.content)
            val required = inputSchema["required"]?.jsonArray
            assertNotNull(required)
            assertTrue(required.any { it.jsonPrimitive.content == "message" })
            assertNotNull(inputSchema["properties"]?.jsonObject?.get("message"))
        }

    @Test
    fun `handles tools call request executing send_discord_message with webhooks`() =
        runTest {
            transaction(database) {
                McpWebhooksTable.insert {
                    it[id] = "wh-1"
                    it[clientId] = "luna-mcp-client"
                    it[tag] = "default"
                    it[guildId] = "g-1"
                    it[channelId] = "c-1"
                    it[webhookUrl] = "https://discord.com/api/webhooks/111/token1"
                    it[createdAt] = System.currentTimeMillis()
                }
            }

            val requestJson =
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"message":"Hello world!"}}}"""
            val response = handler.handle(requestJson)
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val result = root["result"]?.jsonObject
            assertNotNull(result)
            val contentArray = result["content"]?.jsonArray
            assertNotNull(contentArray)
            assertEquals(1, contentArray.size)

            val contentObj = contentArray[0].jsonObject
            assertEquals("text", contentObj["type"]?.jsonPrimitive?.content)
            val text = contentObj["text"]?.jsonPrimitive?.content.orEmpty()
            val payload = Json.parseToJsonElement(text).jsonObject
            assertEquals("success", payload["status"]?.jsonPrimitive?.content)
            assertEquals(1, payload["delivered_channels"]?.jsonPrimitive?.content?.toInt())
            assertEquals(false, result["isError"]?.jsonPrimitive?.booleanOrNull)
        }

    @Test
    fun `handles tools call request when no channels are registered`() =
        runTest {
            val requestJson =
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"message":"Hello world!"}}}"""
            val response = handler.handle(requestJson)
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val result = root["result"]?.jsonObject
            assertNotNull(result)
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
            assertEquals("no_channels_registered", payload["status"]?.jsonPrimitive?.content)
            assertEquals(0, payload["delivered_channels"]?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun `returns json-rpc error when unknown tool is requested`() =
        runTest {
            val requestJson =
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"unknown_tool","arguments":{}}}"""
            val response = handler.handle(requestJson)
            assertNotNull(response)

            val root = Json.parseToJsonElement(response).jsonObject
            val error = root["error"]?.jsonObject
            assertNotNull(error)
            assertEquals(-32601, error["code"]?.jsonPrimitive?.content?.toInt())
        }
}
