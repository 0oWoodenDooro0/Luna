package luna.mcp.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
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
                val endpointDeferred = CompletableDeferred<String>()
                val messageDeferred = CompletableDeferred<String>()
                val collectJob =
                    launch {
                        incoming.collect { sseEvent ->
                            if (sseEvent.event == "endpoint" && !endpointDeferred.isCompleted) {
                                endpointDeferred.complete(sseEvent.data.orEmpty())
                            } else if (sseEvent.event == "message" && !messageDeferred.isCompleted) {
                                messageDeferred.complete(sseEvent.data.orEmpty())
                            }
                        }
                    }

                val endpointUri = endpointDeferred.await()
                assertTrue(endpointUri.startsWith("/mcp/messages?sessionId="))

                val postResponse =
                    client.post(endpointUri) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":99,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""")
                    }
                assertEquals(HttpStatusCode.OK, postResponse.status)

                val messageData = messageDeferred.await()
                collectJob.cancel()

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
                val endpointDeferred = CompletableDeferred<String>()
                val collectJob =
                    launch {
                        incoming.collect { event ->
                            if (event.event == "endpoint" && !endpointDeferred.isCompleted) {
                                endpointDeferred.complete(event.data.orEmpty())
                            }
                        }
                    }

                val endpointUri = endpointDeferred.await()
                assertTrue(endpointUri.startsWith("/mcp/messages?sessionId="))

                val postResponse =
                    client.post(endpointUri) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":30,"method":"tools/call","params":{"name":"list_targets","arguments":{}}}""")
                    }
                collectJob.cancel()
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
