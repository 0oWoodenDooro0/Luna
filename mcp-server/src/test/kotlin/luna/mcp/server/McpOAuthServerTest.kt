package luna.mcp.server

import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.mcp.data.McpClientRecord
import luna.mcp.data.McpClientRepository
import luna.mcp.data.McpWebhookRepository
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpOAuthServerTest {
    private lateinit var database: Database
    private lateinit var repository: McpWebhookRepository
    private lateinit var clientRepository: McpClientRepository

    @BeforeTest
    fun setUp() {
        val dbName = "test_oauth_${UUID.randomUUID()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        repository = McpWebhookRepository(database)
        clientRepository = McpClientRepository(database)
    }

    private fun registerTestClient(
        clientId: String = "test-client-id",
        secret: String? = null,
    ): McpClientRecord {
        val salt = if (secret != null) "test-salt" else ""
        val hash = if (secret != null) McpOAuthSecurity.hashSecret(secret, salt) else ""
        val now = System.currentTimeMillis()
        val client =
            McpClientRecord(
                clientId = clientId,
                userId = "user-$clientId",
                clientName = "Client $clientId",
                secretHash = hash,
                secretSalt = salt,
                createdAt = now,
                updatedAt = now,
            )
        clientRepository.createClient(client)
        return client
    }

    @Test
    fun `revokeClientTokens purges tokens and causes subsequent token validation to fail`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(config = authConfig, clientRepository = clientRepository)
            val clientId = "client-to-revoke"
            val now = System.currentTimeMillis()
            clientRepository.createClient(
                McpClientRecord(
                    clientId = clientId,
                    userId = "user-to-revoke",
                    clientName = "Revoke Test Client",
                    secretHash = "dummy-hash",
                    secretSalt = "dummy-salt",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val issuedToken = provider.issueAccessToken(clientId)

            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    authConfig = authConfig,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            // Before revocation: token is valid and returns client ID
            assertTrue(provider.isValidAccessToken(issuedToken.token))
            assertEquals(clientId, provider.getClientIdFromToken(issuedToken.token))

            val responseBefore =
                client.post("/mcp/messages") {
                    headers.append(io.ktor.http.HttpHeaders.Authorization, "Bearer ${issuedToken.token}")
                    contentType(io.ktor.http.ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""")
                }
            assertEquals(HttpStatusCode.OK, responseBefore.status)

            // Revoke client tokens
            provider.revokeClientTokens(clientId)

            // After revocation: token is no longer valid
            kotlin.test.assertFalse(provider.isValidAccessToken(issuedToken.token))
            kotlin.test.assertNull(provider.getClientIdFromToken(issuedToken.token))

            val responseAfter =
                client.post("/mcp/messages") {
                    headers.append(io.ktor.http.HttpHeaders.Authorization, "Bearer ${issuedToken.token}")
                    contentType(io.ktor.http.ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, responseAfter.status)
        }

    @Test
    fun `GET mcp sse rejects unauthenticated request with 401 and WWW-Authenticate header`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig()
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
            }

            val response = client.get("/mcp/sse")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("""Bearer error="invalid_token"""", response.headers[io.ktor.http.HttpHeaders.WWWAuthenticate])
        }

    @Test
    fun `GET mcp sse accepts valid Bearer token in Authorization header`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(authConfig)
            val issuedToken = provider.issueAccessToken("test-client")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
            }

            val sseClient =
                createClient {
                    install(io.ktor.client.plugins.sse.SSE)
                }

            sseClient.sse("/mcp/sse", request = {
                header(io.ktor.http.HttpHeaders.Authorization, "Bearer ${issuedToken.token}")
            }) {
                val event = incoming.first()
                assertEquals("endpoint", event.event)
            }
        }

    @Test
    fun `GET mcp sse accepts valid Bearer token in query parameter`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(authConfig)
            val issuedToken = provider.issueAccessToken("test-client")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
            }

            val sseClient =
                createClient {
                    install(io.ktor.client.plugins.sse.SSE)
                }

            sseClient.sse("/mcp/sse?token=${issuedToken.token}") {
                val event = incoming.first()
                assertEquals("endpoint", event.event)
            }
        }

    @Test
    fun `POST mcp messages rejects unauthenticated request with 401 and WWW-Authenticate header`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig()
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
            }

            val request = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
            val response =
                client.post("/mcp/messages") {
                    contentType(io.ktor.http.ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("""Bearer error="invalid_token"""", response.headers[io.ktor.http.HttpHeaders.WWWAuthenticate])
        }

    @Test
    fun `POST mcp messages rejects invalid Bearer token with 401`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig()
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
            }

            val request = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(io.ktor.http.HttpHeaders.Authorization, "Bearer invalid-token-1234")
                    contentType(io.ktor.http.ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("""Bearer error="invalid_token"""", response.headers[io.ktor.http.HttpHeaders.WWWAuthenticate])
        }

    @Test
    fun `POST mcp messages accepts valid Bearer token in Authorization header`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(authConfig)
            val issuedToken = provider.issueAccessToken("test-client")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
            }

            val request = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
            val response =
                client.post("/mcp/messages") {
                    headers.append(io.ktor.http.HttpHeaders.Authorization, "Bearer ${issuedToken.token}")
                    contentType(io.ktor.http.ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST oauth token with dynamic client refresh_token verifies client secret and issues refreshed access_token`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val plainSecret = "dyn-refresh-secret-789"
            val salt = "salt-refresh-789"
            val hash = McpOAuthSecurity.hashSecret(plainSecret, salt)
            val clientId = "dyn-refresh-client"
            val now = System.currentTimeMillis()

            val clientRecord =
                McpClientRecord(
                    clientId = clientId,
                    userId = "user-refresh-789",
                    clientName = "Refresh Test Client",
                    secretHash = hash,
                    secretSalt = salt,
                    createdAt = now,
                    updatedAt = now,
                )
            clientRepository.createClient(clientRecord)

            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            val redirectUri = "https://oauth-redirect.googleusercontent.com/r/refresh-test"
            val code = provider.issueAuthorizationCode(clientId, redirectUri, null, null)

            val tokenResponse =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("client_id", clientId)
                                append("client_secret", plainSecret)
                                append("redirect_uri", redirectUri)
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, tokenResponse.status)
            val tokenJson = Json.parseToJsonElement(tokenResponse.bodyAsText()).jsonObject
            val initialAccessToken = tokenJson["access_token"]?.jsonPrimitive?.content
            val refreshToken = tokenJson["refresh_token"]?.jsonPrimitive?.content
            assertNotNull(initialAccessToken)
            assertNotNull(refreshToken)
            assertEquals(clientId, provider.getClientIdFromToken(initialAccessToken))

            // Refresh with valid secret succeeds
            val refreshSuccessResponse =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "refresh_token")
                                append("refresh_token", refreshToken)
                                append("client_id", clientId)
                                append("client_secret", plainSecret)
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, refreshSuccessResponse.status)
            val refreshSuccessJson = Json.parseToJsonElement(refreshSuccessResponse.bodyAsText()).jsonObject
            val refreshedAccessToken = refreshSuccessJson["access_token"]?.jsonPrimitive?.content
            assertNotNull(refreshedAccessToken)
            assertEquals(clientId, provider.getClientIdFromToken(refreshedAccessToken))

            // Refresh with wrong secret returns 401 invalid_client
            val refreshFailResponse =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "refresh_token")
                                append("refresh_token", refreshToken)
                                append("client_id", clientId)
                                append("client_secret", "bad-secret")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Unauthorized, refreshFailResponse.status)
            val failJson = Json.parseToJsonElement(refreshFailResponse.bodyAsText()).jsonObject
            assertEquals("invalid_client", failJson["error"]?.jsonPrimitive?.content)
            assertEquals("Client authentication failed", failJson["error_description"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST oauth token with client_secret but omitted client_id in body succeeds via authCode clientId`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val clientId = "dyn-omitted-client-id"
            val plainSecret = "valid-secret-123"
            registerTestClient(clientId, plainSecret)

            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            val redirectUri = "https://oauth-redirect.googleusercontent.com/r/test-project"
            val code = provider.issueAuthorizationCode(clientId, redirectUri, null, null)

            // Client sends client_secret but omits client_id in the body
            val response =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", redirectUri)
                                append("client_secret", plainSecret)
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val accessToken = json["access_token"]?.jsonPrimitive?.content
            assertNotNull(accessToken)
            assertTrue(accessToken.startsWith("luna_at_"))
            assertEquals(clientId, provider.getClientIdFromToken(accessToken))
        }

    @Test
    fun `POST oauth token with grant_type client_credentials and valid credentials returns access token`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val clientId = "m2m-gemini-client"
            val plainSecret = "m2m-secret-xyz"
            registerTestClient(clientId, plainSecret)

            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            val response =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "client_credentials")
                                append("client_id", clientId)
                                append("client_secret", plainSecret)
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val accessToken = json["access_token"]?.jsonPrimitive?.content
            assertNotNull(accessToken)
            assertTrue(accessToken.startsWith("luna_at_"))
            assertEquals("Bearer", json["token_type"]?.jsonPrimitive?.content)
            assertEquals(clientId, provider.getClientIdFromToken(accessToken))
        }
}
