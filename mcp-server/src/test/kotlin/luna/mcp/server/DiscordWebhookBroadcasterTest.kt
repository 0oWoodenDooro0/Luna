package luna.mcp.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscordWebhookBroadcasterTest {
    private lateinit var database: Database
    private lateinit var repository: McpWebhookRepository

    @BeforeTest
    fun setUp() {
        val dbName = "test_broadcaster_${UUID.randomUUID()}"
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
                it[McpWebhooksTable.clientId] = "luna-mcp-client"
                it[McpWebhooksTable.tag] = "tag-$id"
                it[McpWebhooksTable.guildId] = "guild-$id"
                it[McpWebhooksTable.channelId] = channelId
                it[McpWebhooksTable.webhookUrl] = url
                it[McpWebhooksTable.createdAt] = System.currentTimeMillis()
            }
        }
    }

    @Test
    fun `returns no channels registered when table is empty`() =
        runTest {
            val client =
                HttpClient(MockEngine { respond("OK", HttpStatusCode.OK) }) {
                    install(ContentNegotiation) { json() }
                }
            val broadcaster = DiscordWebhookBroadcaster(repository, client)

            val result = broadcaster.broadcast("Hello World")
            assertEquals("no_channels_registered", result.status)
            assertEquals(0, result.deliveredChannels)
            assertEquals("""{"status":"no_channels_registered","delivered_channels":0}""", result.toJsonString())
        }

    @Test
    fun `broadcasts payload with Gemini username and avatar to active webhook`() =
        runTest {
            addWebhook("wh-1", "chan-1", "https://discord.com/api/webhooks/111/token1")

            val capturedRequests = mutableListOf<String>()
            val capturedContentTypes = mutableListOf<String?>()

            val mockEngine =
                MockEngine { request ->
                    val bytes =
                        (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()
                            ?: ByteArray(0)
                    val content = bytes.decodeToString()
                    val contentType =
                        request.body.contentType?.toString()
                            ?: request.headers[HttpHeaders.ContentType]
                    capturedContentTypes.add(contentType)
                    capturedRequests.add(content)
                    respond("{}", HttpStatusCode.NoContent, headersOf(HttpHeaders.ContentType, "application/json"))
                }

            val client =
                HttpClient(mockEngine) {
                    install(ContentNegotiation) { json() }
                }
            val broadcaster = DiscordWebhookBroadcaster(repository, client)

            val result = broadcaster.broadcast("Test message content")
            assertEquals("success", result.status)
            assertEquals(1, result.deliveredChannels)
            assertEquals("""{"status":"success","delivered_channels":1}""", result.toJsonString())

            assertEquals(1, capturedRequests.size)
            val payload = Json.parseToJsonElement(capturedRequests[0]).jsonObject
            assertEquals("Test message content", payload["content"]?.jsonPrimitive?.content)
            assertEquals("Gemini", payload["username"]?.jsonPrimitive?.content)
            assertEquals("https://www.gstatic.com/lamda/images/gemini_sparkle_v002_d4735304ff6292a690345.svg", payload["avatar_url"]?.jsonPrimitive?.content)
            assertTrue(capturedContentTypes[0]?.contains("application/json") == true)
        }

    @Test
    fun `broadcasts to multiple webhooks and counts delivered channels`() =
        runTest {
            addWebhook("wh-1", "chan-1", "https://discord.com/api/webhooks/111/token1")
            addWebhook("wh-2", "chan-2", "https://discord.com/api/webhooks/222/token2")

            val dispatchedUrls = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    dispatchedUrls.add(request.url.toString())
                    respond("{}", HttpStatusCode.NoContent)
                }
            val client =
                HttpClient(mockEngine) {
                    install(ContentNegotiation) { json() }
                }
            val broadcaster = DiscordWebhookBroadcaster(repository, client)

            val result = broadcaster.broadcast("Hello to all channels")
            assertEquals("success", result.status)
            assertEquals(2, result.deliveredChannels)
            assertEquals(2, dispatchedUrls.size)
            assertTrue(dispatchedUrls.contains("https://discord.com/api/webhooks/111/token1"))
            assertTrue(dispatchedUrls.contains("https://discord.com/api/webhooks/222/token2"))
        }

    @Test
    fun `broadcasts partitioned chunks sequentially when message exceeds 2000 characters`() =
        runTest {
            addWebhook("wh-1", "chan-1", "https://discord.com/api/webhooks/111/token1")

            val chunk1 = "x".repeat(1500)
            val chunk2 = "y".repeat(1000)
            val largeMessage = "$chunk1\n$chunk2"

            val dispatchedContents = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    val bytes =
                        (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()
                            ?: ByteArray(0)
                    val jsonPayload = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                    dispatchedContents.add(jsonPayload["content"]?.jsonPrimitive?.content.orEmpty())
                    respond("{}", HttpStatusCode.NoContent)
                }
            val client =
                HttpClient(mockEngine) {
                    install(ContentNegotiation) { json() }
                }
            val broadcaster = DiscordWebhookBroadcaster(repository, client)

            val result = broadcaster.broadcast(largeMessage)
            assertEquals("success", result.status)
            assertEquals(1, result.deliveredChannels)
            assertEquals(2, dispatchedContents.size)
            assertEquals(chunk1, dispatchedContents[0])
            assertEquals(chunk2, dispatchedContents[1])
        }

    @Test
    fun `handles partial failure when one webhook returns 500 server error`() =
        runTest {
            addWebhook("wh-1", "chan-1", "https://discord.com/api/webhooks/111/good")
            addWebhook("wh-2", "chan-2", "https://discord.com/api/webhooks/222/bad")

            val mockEngine =
                MockEngine { request ->
                    if (request.url.toString().contains("bad")) {
                        respond("Internal Server Error", HttpStatusCode.InternalServerError)
                    } else {
                        respond("{}", HttpStatusCode.NoContent)
                    }
                }
            val client =
                HttpClient(mockEngine) {
                    install(ContentNegotiation) { json() }
                }
            val broadcaster = DiscordWebhookBroadcaster(repository, client)

            val result = broadcaster.broadcast("Test resilience")
            assertEquals("success", result.status)
            assertEquals(1, result.deliveredChannels)
        }
}
