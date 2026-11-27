package luna.mcp.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import luna.mcp.data.McpClientRecord
import luna.mcp.data.McpClientRepository
import luna.mcp.data.McpWebhookRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerPluginTest {
    private val allowedOwnerId = "owner-allowed"
    private val blockedOwnerId = "owner-blocked"

    private fun makeTestPlugin(pluginEnabledFor: (McpAuthContext) -> Boolean = { true }): McpServerPlugin =
        object : McpServerPlugin {
            override val name: String = "test-plugin"

            override fun isEnabledFor(auth: McpAuthContext): Boolean = pluginEnabledFor(auth)

            override fun tools(): List<McpTool> =
                listOf(
                    object : McpTool {
                        override val name: String = "public_tool"
                        override val description: String = "Always visible"
                        override val inputSchema: JsonObject =
                            buildJsonObject {
                                put("type", "object")
                            }

                        override suspend fun execute(
                            arguments: JsonObject?,
                            auth: McpAuthContext,
                        ): McpToolResult = McpToolResult.Text("public:${auth.clientId}:${auth.ownerId}")
                    },
                    object : McpTool {
                        override val name: String = "restricted_tool"
                        override val description: String = "Only visible to allowed owner"
                        override val inputSchema: JsonObject =
                            buildJsonObject {
                                put("type", "object")
                            }

                        override fun isVisibleTo(auth: McpAuthContext): Boolean = auth.ownerId == allowedOwnerId

                        override suspend fun execute(
                            arguments: JsonObject?,
                            auth: McpAuthContext,
                        ): McpToolResult = McpToolResult.Text("restricted:${auth.ownerId}")
                    },
                )

            override fun registerRoutes(route: Route) {
                route.get("/plugin-health") {
                    call.respondText("ok")
                }
            }
        }

    private fun makeBroadcaster(): McpWebhookRepository {
        val dbName = "test_plugin_${UUID.randomUUID()}"
        val db = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        return McpWebhookRepository(db)
    }

    private fun makeHttpClient(): HttpClient =
        HttpClient(MockEngine { respond("{}", HttpStatusCode.NoContent) }) {
            install(ContentNegotiation) { json() }
        }

    @Test
    fun `tools list filters tools by McpAuthContext visibility`() =
        runTest {
            val handler = McpProtocolHandler(plugins = listOf(makeTestPlugin()))

            val listRequest = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""

            val allowedResponse = handler.handle(listRequest, McpAuthContext(clientId = "c1", ownerId = allowedOwnerId))
            assertNotNull(allowedResponse)
            val allowedTools =
                Json
                    .parseToJsonElement(allowedResponse)
                    .jsonObject["result"]
                    ?.jsonObject
                    ?.get("tools")
                    ?.jsonArray
            assertNotNull(allowedTools)
            assertEquals(
                listOf("public_tool", "restricted_tool"),
                allowedTools.map { it.jsonObject["name"]?.jsonPrimitive?.content },
            )

            val blockedResponse = handler.handle(listRequest, McpAuthContext(clientId = "c2", ownerId = blockedOwnerId))
            assertNotNull(blockedResponse)
            val blockedTools =
                Json
                    .parseToJsonElement(blockedResponse)
                    .jsonObject["result"]
                    ?.jsonObject
                    ?.get("tools")
                    ?.jsonArray
            assertNotNull(blockedTools)
            assertEquals(
                listOf("public_tool"),
                blockedTools.map { it.jsonObject["name"]?.jsonPrimitive?.content },
            )
        }

    @Test
    fun `tools call dispatches visible tool with auth context and rejects hidden tool with -32601`() =
        runTest {
            val handler = McpProtocolHandler(plugins = listOf(makeTestPlugin()))

            val callRestricted =
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"restricted_tool","arguments":{}}}"""

            val allowedRaw = handler.handle(callRestricted, McpAuthContext(clientId = "c1", ownerId = allowedOwnerId))
            assertNotNull(allowedRaw)
            val allowedResult = Json.parseToJsonElement(allowedRaw).jsonObject["result"]?.jsonObject
            assertNotNull(allowedResult)
            assertEquals(false, allowedResult["isError"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(
                "restricted:$allowedOwnerId",
                allowedResult["content"]
                    ?.jsonArray
                    ?.first()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content,
            )

            val blockedRaw = handler.handle(callRestricted, McpAuthContext(clientId = "c2", ownerId = blockedOwnerId))
            assertNotNull(blockedRaw)
            val blockedError = Json.parseToJsonElement(blockedRaw).jsonObject["error"]?.jsonObject
            assertNotNull(blockedError)
            assertEquals(-32601, blockedError["code"]?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun `plugin-level isEnabledFor hides all plugin tools from list and call`() =
        runTest {
            val handler =
                McpProtocolHandler(
                    plugins = listOf(makeTestPlugin(pluginEnabledFor = { it.ownerId == allowedOwnerId })),
                )

            val listReq = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
            val blockedList = handler.handle(listReq, McpAuthContext(clientId = "c2", ownerId = blockedOwnerId))
            assertNotNull(blockedList)
            val tools =
                Json
                    .parseToJsonElement(blockedList)
                    .jsonObject["result"]
                    ?.jsonObject
                    ?.get("tools")
                    ?.jsonArray
            assertNotNull(tools)
            assertEquals(0, tools.size)

            val callReq = """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"public_tool","arguments":{}}}"""
            val blockedCall = handler.handle(callReq, McpAuthContext(clientId = "c2", ownerId = blockedOwnerId))
            assertNotNull(blockedCall)
            val error = Json.parseToJsonElement(blockedCall).jsonObject["error"]?.jsonObject
            assertNotNull(error)
            assertEquals(-32601, error["code"]?.jsonPrimitive?.content?.toInt())
        }

    @Test
    fun `mcpServerModule resolves ownerId from McpClientRepository and registers plugin routes`() =
        testApplication {
            val dbName = "test_routes_plugin_${UUID.randomUUID()}"
            val db = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
            val webhookRepo = McpWebhookRepository(db)
            val clientRepo = McpClientRepository(db)
            val oAuthProvider = McpOAuthProvider(clientRepository = clientRepo)
            val broadcaster = DiscordWebhookBroadcaster(webhookRepo, makeHttpClient())

            val now = System.currentTimeMillis()
            clientRepo.createClient(
                McpClientRecord(
                    clientId = "client-1",
                    userId = allowedOwnerId,
                    clientName = "Client 1",
                    secretHash = "",
                    secretSalt = "",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val token = oAuthProvider.issueAccessToken("client-1").token

            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    clientRepository = clientRepo,
                    oAuthProvider = oAuthProvider,
                    plugins = listOf(makeTestPlugin()),
                )
            }

            val healthResponse = client.get("/plugin-health")
            assertEquals(HttpStatusCode.OK, healthResponse.status)
            assertEquals("ok", healthResponse.bodyAsText())

            val toolResponse =
                client.post("/mcp/messages") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"restricted_tool","arguments":{}}}""")
                }
            assertEquals(HttpStatusCode.OK, toolResponse.status)
            val text =
                Json
                    .parseToJsonElement(toolResponse.bodyAsText())
                    .jsonObject["result"]
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonArray
                    ?.first()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content
            assertEquals("restricted:$allowedOwnerId", text)
        }

    @Test
    fun `mcpServerModule logs warning and leaves ownerId null when clientRepository is absent`() =
        testApplication {
            val webhookRepo = makeBroadcaster()
            val broadcaster = DiscordWebhookBroadcaster(webhookRepo, makeHttpClient())
            val sessionManager = McpSessionManager()
            sessionManager.registerSession("sess-1", Channel(capacity = 8), "legacy-client")

            val logger = LoggerFactory.getLogger("luna.mcp.server.McpServerRoutes") as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                application {
                    mcpServerModule(
                        broadcaster = broadcaster,
                        sessionManager = sessionManager,
                        clientRepository = null,
                        plugins = listOf(makeTestPlugin()),
                    )
                }

                val response =
                    client.post("/mcp/messages?sessionId=sess-1") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"public_tool","arguments":{}}}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val text =
                    Json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonObject["result"]
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonArray
                        ?.first()
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.content
                assertEquals("public:legacy-client:null", text)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains("McpClientRepository is absent")
                    },
                    "Expected WARN log when McpClientRepository is absent",
                )
            } finally {
                logger.detachAppender(listAppender)
            }
        }

    @Test
    fun `resolveMcpAuthContext logs warning and leaves ownerId null when client record is missing`() {
        val dbName = "test_missing_client_${UUID.randomUUID()}"
        val db = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        val clientRepo = McpClientRepository(db)

        val logger = LoggerFactory.getLogger("luna.mcp.server.McpServerRoutes") as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        logger.addAppender(listAppender)

        try {
            val auth = resolveMcpAuthContext("missing-client", clientRepo, logger)
            assertEquals("missing-client", auth.clientId)
            assertNull(auth.ownerId)
            assertTrue(
                listAppender.list.any {
                    it.level == Level.WARN && it.formattedMessage.contains("MCP client record not found")
                },
                "Expected WARN log when client record is missing",
            )
        } finally {
            logger.detachAppender(listAppender)
        }
    }
}
