package luna.mcp.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.head
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
import luna.mcp.data.McpClientRecord
import luna.mcp.data.McpClientRepository
import luna.mcp.data.McpWebhookRecord
import luna.mcp.data.McpWebhookRepository
import org.jetbrains.exposed.v1.jdbc.Database
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
    private lateinit var clientRepository: McpClientRepository
    private lateinit var oAuthProvider: McpOAuthProvider
    private lateinit var defaultToken: String

    @BeforeTest
    fun setUp() {
        val dbName = "test_server_${UUID.randomUUID()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        repository = McpWebhookRepository(database)
        clientRepository = McpClientRepository(database)
        oAuthProvider = McpOAuthProvider(clientRepository = clientRepository)

        val now = System.currentTimeMillis()
        clientRepository.createClient(
            McpClientRecord(
                clientId = "test-client",
                userId = "test-user",
                clientName = "Test Client",
                secretHash = "",
                secretSalt = "",
                createdAt = now,
                updatedAt = now,
            ),
        )
        defaultToken = oAuthProvider.issueAccessToken("test-client").token
    }

    private fun issueClientToken(clientId: String): String {
        val now = System.currentTimeMillis()
        clientRepository.createClient(
            McpClientRecord(
                clientId = clientId,
                userId = "user-$clientId",
                clientName = "Client $clientId",
                secretHash = "",
                secretSalt = "",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return oAuthProvider.issueAccessToken(clientId).token
    }

    private fun addWebhook(
        id: String,
        channelId: String,
        url: String,
        clientId: String = "test-client",
    ) {
        repository.addWebhook(
            McpWebhookRecord(
                id = id,
                clientId = clientId,
                tag = "tag-$id",
                guildId = "g-$id",
                channelId = channelId,
                webhookUrl = url,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    @Test
    fun `GET mcp sse establishes stream and emits endpoint event`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val sseClient =
                createClient {
                    install(io.ktor.client.plugins.sse.SSE)
                }

            sseClient.sse("/mcp/sse?token=$defaultToken") {
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
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val sseClient =
                createClient {
                    install(io.ktor.client.plugins.sse.SSE)
                }

            sseClient.sse("/mcp/sse?token=$defaultToken") {
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
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val request =
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(HttpHeaders.Authorization, "Bearer $defaultToken")
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
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val notification = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(HttpHeaders.Authorization, "Bearer $defaultToken")
                    contentType(ContentType.Application.Json)
                    setBody(notification)
                }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

    @Test
    fun `POST mcp messages handles tools list returning send_discord_message and list_targets`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val request = """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(HttpHeaders.Authorization, "Bearer $defaultToken")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val tools = jsonResponse["result"]?.jsonObject?.get("tools")?.jsonArray
            assertNotNull(tools)
            assertEquals(2, tools.size)
            assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "send_discord_message" })
            assertTrue(tools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "list_targets" })
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
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val request =
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"content":"Broadcast to Discord","target":"all"}}}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(HttpHeaders.Authorization, "Bearer $defaultToken")
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
            assertEquals("✅ 已成功將訊息發送至 2 個頻道 [tag-wh-1, tag-wh-2]", text)

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
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val part1 = "p1".repeat(700)
            val part2 = "p2".repeat(600)
            val message = "$part1\n$part2"

            val request =
                """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"content":${Json.encodeToString(
                    message,
                )},"target":"all"}}}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(HttpHeaders.Authorization, "Bearer $defaultToken")
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
            assertEquals("✅ 已成功將訊息發送至 1 個頻道 [tag-wh-1]", text)

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
    fun `POST mcp messages tools call when empty webhooks returns guidance error`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val request =
                """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"content":"Hello","target":"all"}}}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(HttpHeaders.Authorization, "Bearer $defaultToken")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val result = jsonResponse["result"]?.jsonObject
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
    fun `POST mcp messages handles list_targets for specific clientId query param`() =
        testApplication {
            repository.addWebhook(McpWebhookRecord("wh-a1", "tenant-a", "ops", "g-1", "c-1", "https://discord.com/api/webhooks/111/ops", createdAt = 100L))
            repository.addWebhook(McpWebhookRecord("wh-b1", "tenant-b", "dev", "g-2", "c-2", "https://discord.com/api/webhooks/222/dev", createdAt = 200L))

            val tokenA = issueClientToken("tenant-a")
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val request = """{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"list_targets","arguments":{}}}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(HttpHeaders.Authorization, "Bearer $tokenA")
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
            val listJson = Json.parseToJsonElement(text).jsonObject
            assertEquals(1, listJson["count"]?.jsonPrimitive?.content?.toInt())
            assertEquals(listOf("ops"), listJson["tags"]?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(10, listJson["maxQuota"]?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun `POST mcp messages handles tag-scoped send_discord_message for specific clientId`() =
        testApplication {
            val dispatchedUrls = mutableListOf<String>()
            val mockHttpClient =
                HttpClient(
                    MockEngine { req ->
                        dispatchedUrls.add(req.url.toString())
                        respond("{}", HttpStatusCode.NoContent)
                    },
                ) {
                    install(ContentNegotiation) { json() }
                }

            repository.addWebhook(McpWebhookRecord("wh-a1", "tenant-a", "ops", "g-1", "c-1", "https://discord.com/api/webhooks/111/ops", createdAt = 100L))
            repository.addWebhook(
                McpWebhookRecord("wh-a2", "tenant-a", "marketing", "g-1", "c-2", "https://discord.com/api/webhooks/111/marketing", createdAt = 200L),
            )

            val tokenA = issueClientToken("tenant-a")
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val request =
                """{"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"content":"Alert!","target":"ops"}}}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(HttpHeaders.Authorization, "Bearer $tokenA")
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
            assertEquals("✅ 已成功將訊息發送至 1 個頻道 [ops]", text)
            assertEquals(listOf("https://discord.com/api/webhooks/111/ops"), dispatchedUrls)
        }

    @Test
    fun `POST mcp messages with unknown tag returns guidance error`() =
        testApplication {
            repository.addWebhook(McpWebhookRecord("wh-1", "tenant-a", "ops", "g-1", "c-1", "https://discord.com/api/webhooks/111/ops", createdAt = 100L))

            val tokenA = issueClientToken("tenant-a")
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val request =
                """{"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"name":"send_discord_message","arguments":{"content":"Hello","target":"unknown"}}}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(HttpHeaders.Authorization, "Bearer $tokenA")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val result = jsonResponse["result"]?.jsonObject
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
            assertEquals("❌ 發送失敗：找不到標籤為 'unknown' 的 Webhook。請先在 Discord 使用 /mcp webhook add tag:unknown 綁定頻道，或調用 list_targets 查看現有可用標籤。", text)
        }

    @Test
    fun `SSE session propagates authenticated clientId to subsequent POST messages`() =
        testApplication {
            repository.addWebhook(
                McpWebhookRecord("wh-sse", "tenant-sse", "sse-tag", "g-sse", "c-sse", "https://discord.com/api/webhooks/sse/url", createdAt = 100L),
            )

            val tokenSse = issueClientToken("tenant-sse")
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val sseClient =
                createClient {
                    install(io.ktor.client.plugins.sse.SSE)
                }

            sseClient.sse("/mcp/sse?token=$tokenSse") {
                val event = incoming.first()
                assertEquals("endpoint", event.event)
                val endpointUri = event.data.orEmpty()
                assertTrue(endpointUri.startsWith("/mcp/messages?sessionId="))

                val postResponse =
                    client.post(endpointUri) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":30,"method":"tools/call","params":{"name":"list_targets","arguments":{}}}""")
                    }
                assertEquals(HttpStatusCode.OK, postResponse.status)
                val jsonResponse = Json.parseToJsonElement(postResponse.bodyAsText()).jsonObject
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
                val listJson = Json.parseToJsonElement(text).jsonObject
                assertEquals(1, listJson["count"]?.jsonPrimitive?.content?.toInt())
                assertEquals(listOf("sse-tag"), listJson["tags"]?.jsonArray?.map { it.jsonPrimitive.content })
            }
        }

    @Test
    fun `POST mcp sse handles JSON-RPC initialize request directly`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val request =
                """{"jsonrpc":"2.0","id":10,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
            val response =
                client.post("/mcp/sse") {
                    headers.append(HttpHeaders.Authorization, "Bearer $defaultToken")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("2.0", jsonResponse["jsonrpc"]?.jsonPrimitive?.content)
            assertEquals(10, jsonResponse["id"]?.jsonPrimitive?.content?.toInt())
            val result = jsonResponse["result"]?.jsonObject
            assertNotNull(result)
            assertEquals("2024-11-05", result["protocolVersion"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST mcp handles ping request`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val request = """{"jsonrpc":"2.0","id":11,"method":"ping"}"""
            val response =
                client.post("/mcp") {
                    headers.append(HttpHeaders.Authorization, "Bearer $defaultToken")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("2.0", jsonResponse["jsonrpc"]?.jsonPrimitive?.content)
            assertEquals(11, jsonResponse["id"]?.jsonPrimitive?.content?.toInt())
            assertNotNull(jsonResponse["result"])
        }

    @Test
    fun `HEAD mcp and HEAD mcp sse return 200 OK`() =
        testApplication {
            val mockHttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) })
            val broadcaster = DiscordWebhookBroadcaster(repository, mockHttpClient)
            application {
                mcpServerModule(broadcaster, clientRepository = clientRepository, oAuthProvider = oAuthProvider)
            }

            val headSse = client.head("/mcp/sse")
            assertEquals(HttpStatusCode.OK, headSse.status)

            val headRoot = client.head("/mcp")
            assertEquals(HttpStatusCode.OK, headRoot.status)
        }
}
