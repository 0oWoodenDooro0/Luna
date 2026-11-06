package luna.mcp.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import kotlin.test.assertTrue

class McpServerTest {
    private lateinit var database: Database
    private lateinit var repository: McpWebhookRepository

    @BeforeTest
    fun setUp() {
        val dbName = "test_server_${UUID.randomUUID()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        repository = McpWebhookRepository(database)
    }

    private fun addWebhook(
        id: String,
        channelId: String,
        url: String,
    ) {
        transaction(database) {
            McpWebhooksTable.insert {
                it[McpWebhooksTable.id] = id
                it[McpWebhooksTable.guildId] = "g-$id"
                it[McpWebhooksTable.channelId] = channelId
                it[McpWebhooksTable.webhookUrl] = url
                it[McpWebhooksTable.createdAt] = System.currentTimeMillis()
            }
        }
    }

    @Test
    fun `GET mcp sse establishes stream and emits endpoint event`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster)
            }

            val sseClient =
                createClient {
                    install(io.ktor.client.plugins.sse.SSE)
                }

            sseClient.sse("/mcp/sse") {
                val event = incoming.first()
                assertEquals("endpoint", event.event)
                assertTrue(event.data?.startsWith("/mcp/messages?sessionId=") == true)
            }
        }

    @Test
    fun `POST mcp messages forwards response to active SSE stream`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster)
            }

            val sseClient =
                createClient {
                    install(io.ktor.client.plugins.sse.SSE)
                }

            sseClient.sse("/mcp/sse") {
                var endpointUri = ""
                var messageData = ""
                val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
                val job =
                    testScope.launch {
                        incoming.collect { sseEvent ->
                            if (sseEvent.event == "endpoint") {
                                endpointUri = sseEvent.data.orEmpty()
                            } else if (sseEvent.event == "message") {
                                messageData = sseEvent.data.orEmpty()
                            }
                        }
                    }

                // Wait until endpoint event received
                while (endpointUri.isEmpty()) {
                    kotlinx.coroutines.delay(10)
                }
                assertTrue(endpointUri.startsWith("/mcp/messages?sessionId="))

                val postResponse =
                    client.post(endpointUri) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":99,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""")
                    }
                assertEquals(HttpStatusCode.OK, postResponse.status)

                // Wait until message event received
                while (messageData.isEmpty()) {
                    kotlinx.coroutines.delay(10)
                }
                job.cancel()

                val json = Json.parseToJsonElement(messageData).jsonObject
                assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
                assertEquals(99, json["id"]?.jsonPrimitive?.content?.toInt())
                assertEquals(
                    "2024-11-05",
                    json["result"]
                        ?.jsonObject
                        ?.get("protocolVersion")
                        ?.jsonPrimitive
                        ?.content,
                )
            }
        }

    @Test
    fun `POST mcp messages handles initialize method`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster)
            }

            val request =
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
            val response =
                client.post("/mcp/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("2.0", jsonResponse["jsonrpc"]?.jsonPrimitive?.content)
            assertEquals(1, jsonResponse["id"]?.jsonPrimitive?.content?.toInt())

            val result = jsonResponse["result"]?.jsonObject
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
        }

    @Test
    fun `POST mcp messages handles notifications initialized with 202 Accepted`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster)
            }

            val notification = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
            val response =
                client.post("/mcp/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(notification)
                }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    @Test
    fun `POST mcp messages handles tools list returning send_discord_message`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster)
            }

            val request = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
            val response =
                client.post("/mcp/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val tools = jsonResponse["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools)
            assertEquals(1, tools.size)
            assertEquals("send_discord_message", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST mcp messages tools call dispatches broadcast to all webhooks`() =
        testApplication {
            addWebhook("wh-1", "chan-1", "https://discord.com/api/webhooks/111/token1")
            addWebhook("wh-2", "chan-2", "https://discord.com/api/webhooks/222/token2")

            val outboundUrls = mutableListOf<String>()
            val outboundPayloads = mutableListOf<String>()

            val mockHttpClient =
                HttpClient(
                    MockEngine { req ->
                        outboundUrls.add(req.url.toString())
                        val bytes =
                            (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()
                                ?: ByteArray(0)
                        outboundPayloads.add(bytes.decodeToString())
                        respond("{}", HttpStatusCode.NoContent, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) {
                    install(ContentNegotiation) { json() }
                }

            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster)
            }

            val request =
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"message":"Broadcast to Discord"}}}"""
            val response =
                client.post("/mcp/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val content =
                jsonResponse["result"]
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
            assertNotNull(content)
            val text = content["text"]?.jsonPrimitive?.content.orEmpty()
            val textJson = Json.parseToJsonElement(text).jsonObject
            assertEquals("success", textJson["status"]?.jsonPrimitive?.content)
            assertEquals(2, textJson["delivered_channels"]?.jsonPrimitive?.content?.toInt())

            assertEquals(2, outboundUrls.size)
            assertTrue(outboundUrls.contains("https://discord.com/api/webhooks/111/token1"))
            assertTrue(outboundUrls.contains("https://discord.com/api/webhooks/222/token2"))

            for (payload in outboundPayloads) {
                val json = Json.parseToJsonElement(payload).jsonObject
                assertEquals("Broadcast to Discord", json["content"]?.jsonPrimitive?.content)
                assertEquals("Gemini", json["username"]?.jsonPrimitive?.content)
                assertEquals("https://www.gstatic.com/lamda/images/gemini_sparkle_v002_d4735304ff6292a690345.svg", json["avatar_url"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `POST mcp messages tools call chunks messages exceeding 2000 characters`() =
        testApplication {
            addWebhook("wh-1", "chan-1", "https://discord.com/api/webhooks/111/token1")

            val outboundPayloads = mutableListOf<String>()
            val mockHttpClient =
                HttpClient(
                    MockEngine { req ->
                        val bytes =
                            (req.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()
                                ?: ByteArray(0)
                        outboundPayloads.add(bytes.decodeToString())
                        respond("{}", HttpStatusCode.NoContent, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) {
                    install(ContentNegotiation) { json() }
                }

            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster)
            }

            val part1 = "p1".repeat(700)
            val part2 = "p2".repeat(600)
            val message = "$part1\n$part2"

            val request =
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"message":${Json.encodeToString(
                    message,
                )}}}}"""
            val response =
                client.post("/mcp/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val text =
                jsonResponse["result"]
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            val textJson = Json.parseToJsonElement(text).jsonObject
            assertEquals("success", textJson["status"]?.jsonPrimitive?.content)
            assertEquals(1, textJson["delivered_channels"]?.jsonPrimitive?.content?.toInt())

            assertEquals(2, outboundPayloads.size)
            val c1 =
                Json
                    .parseToJsonElement(outboundPayloads[0])
                    .jsonObject["content"]
                    ?.jsonPrimitive
                    ?.content
            val c2 =
                Json
                    .parseToJsonElement(outboundPayloads[1])
                    .jsonObject["content"]
                    ?.jsonPrimitive
                    ?.content
            assertEquals(part1, c1)
            assertEquals(part2, c2)
        }

    @Test
    fun `POST mcp messages tools call when empty webhooks returns no_channels_registered`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster)
            }

            val request =
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"message":"Hello"}}}"""
            val response =
                client.post("/mcp/messages") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val text =
                jsonResponse["result"]
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonArray
                    ?.get(0)
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            val textJson = Json.parseToJsonElement(text).jsonObject
            assertEquals("no_channels_registered", textJson["status"]?.jsonPrimitive?.content)
            assertEquals(0, textJson["delivered_channels"]?.jsonPrimitive?.content?.toInt())
        }
}
