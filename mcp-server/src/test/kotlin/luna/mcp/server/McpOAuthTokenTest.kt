package luna.mcp.server

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
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

class McpOAuthTokenTest {
    private lateinit var database: Database
    private lateinit var repository: McpWebhookRepository
    private lateinit var clientRepository: McpClientRepository

    @BeforeTest
    fun setUp() {
        val dbName = "test_oauth_token_${UUID.randomUUID()}"
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
    fun `POST oauth token with valid authorization code and PKCE verifier returns access token`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id", "test-secret")
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(authConfig, clientRepository = clientRepository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    authConfig = authConfig,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            // S256 PKCE pair: verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
            // SHA-256 base64url of verifier is "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
            val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
            val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
            val redirectUri = "https://oauth-redirect.googleusercontent.com/r/my-project"

            val code =
                provider.issueAuthorizationCode(
                    clientId = "test-client-id",
                    redirectUri = redirectUri,
                    codeChallenge = challenge,
                    codeChallengeMethod = "S256",
                )

            val response =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", redirectUri)
                                append("client_id", "test-client-id")
                                append("client_secret", "test-secret")
                                append("code_verifier", verifier)
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
            assertEquals(3600, json["expires_in"]?.jsonPrimitive?.content?.toInt())
            assertTrue(provider.isValidAccessToken(accessToken))
            assertEquals("test-client-id", provider.getClientIdFromToken(accessToken))
        }

    @Test
    fun `POST oauth token with Basic authentication header succeeds`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id", "test-secret")
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(authConfig, clientRepository = clientRepository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    authConfig = authConfig,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            val redirectUri = "http://localhost:8080/callback"
            val code =
                provider.issueAuthorizationCode(
                    clientId = "test-client-id",
                    redirectUri = redirectUri,
                    codeChallenge = null,
                    codeChallengeMethod = null,
                )

            val basicCredentials =
                java.util.Base64
                    .getEncoder()
                    .encodeToString("test-client-id:test-secret".toByteArray())

            val response =
                client.post("/oauth/token") {
                    headers.append(io.ktor.http.HttpHeaders.Authorization, "Basic $basicCredentials")
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", redirectUri)
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val accessToken = json["access_token"]?.jsonPrimitive?.content
            assertNotNull(accessToken)
            assertTrue(provider.isValidAccessToken(accessToken))
        }

    @Test
    fun `POST oauth token with invalid client_secret returns 401 invalid_client`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id", "correct-secret")
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(authConfig, clientRepository = clientRepository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    authConfig = authConfig,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            val response =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", "some-code")
                                append("redirect_uri", "http://localhost:8080/callback")
                                append("client_id", "test-client-id")
                                append("client_secret", "wrong-secret")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_client", json["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST oauth token with invalid authorization code returns 400 invalid_grant`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id", "secret")
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(authConfig, clientRepository = clientRepository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    authConfig = authConfig,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            val response =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", "non-existent-code")
                                append("redirect_uri", "http://localhost:8080/callback")
                                append("client_id", "test-client-id")
                                append("client_secret", "secret")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_grant", json["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST oauth token with mismatched PKCE verifier returns 400 invalid_grant`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id")
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(authConfig, clientRepository = clientRepository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    authConfig = authConfig,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
            val redirectUri = "http://localhost:8080/callback"
            val code =
                provider.issueAuthorizationCode(
                    clientId = "test-client-id",
                    redirectUri = redirectUri,
                    codeChallenge = challenge,
                    codeChallengeMethod = "S256",
                )

            val response =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", redirectUri)
                                append("client_id", "test-client-id")
                                append("code_verifier", "wrong-verifier-123")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_grant", json["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST oauth token with code reused returns 400 invalid_grant`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id")
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(authConfig, clientRepository = clientRepository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    authConfig = authConfig,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            val redirectUri = "http://localhost:8080/callback"
            val code =
                provider.issueAuthorizationCode(
                    clientId = "test-client-id",
                    redirectUri = redirectUri,
                    codeChallenge = "my-plain-verifier",
                    codeChallengeMethod = "plain",
                )

            // First exchange succeeds
            val response1 =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", redirectUri)
                                append("client_id", "test-client-id")
                                append("code_verifier", "my-plain-verifier")
                            },
                        ),
                    )
                }
            assertEquals(HttpStatusCode.OK, response1.status)

            // Second exchange with same code must fail
            val response2 =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", redirectUri)
                                append("client_id", "test-client-id")
                                append("code_verifier", "my-plain-verifier")
                            },
                        ),
                    )
                }
            assertEquals(HttpStatusCode.BadRequest, response2.status)
            val json = Json.parseToJsonElement(response2.bodyAsText()).jsonObject
            assertEquals("invalid_grant", json["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST oauth token with unsupported grant_type returns 400 unsupported_grant_type`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id")
            val authConfig = McpOAuthConfig()
            val provider = McpOAuthProvider(authConfig, clientRepository = clientRepository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    authConfig = authConfig,
                    clientRepository = clientRepository,
                    oAuthProvider = provider,
                )
            }

            val response =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "password")
                                append("client_id", "test-client-id")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("unsupported_grant_type", json["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST oauth token with client credentials containing whitespace trims and succeeds`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val clientId = "whitespace-token-client"
            val plainSecret = "whitespace-token-secret"
            registerTestClient(clientId, plainSecret)

            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(broadcaster = broadcaster, clientRepository = clientRepository, oAuthProvider = provider)
            }

            val redirectUri = "https://oauth-redirect.googleusercontent.com/r/my-project"
            val code = provider.issueAuthorizationCode(clientId, redirectUri, null, null)

            val response =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", redirectUri)
                                append("client_id", "  whitespace-token-client  ")
                                append("client_secret", "  whitespace-token-secret  ")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `POST oauth token with percent-encoded credentials in Basic Auth header succeeds per RFC 6749`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val clientId = "rfc-encoded-client"
            val plainSecret = "secret-with special=chars&more"
            registerTestClient(clientId, plainSecret)

            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(broadcaster = broadcaster, clientRepository = clientRepository, oAuthProvider = provider)
            }

            val redirectUri = "https://oauth-redirect.googleusercontent.com/r/my-project"
            val code = provider.issueAuthorizationCode(clientId, redirectUri, null, null)

            // Encode according to RFC 6749 Section 2.3.1 (application/x-www-form-urlencoded percent-encoded)
            val encodedClientId = java.net.URLEncoder.encode(clientId, "UTF-8")
            val encodedSecret = java.net.URLEncoder.encode(plainSecret, "UTF-8")
            val basicCredentials =
                java.util.Base64
                    .getEncoder()
                    .encodeToString("$encodedClientId:$encodedSecret".toByteArray())

            val response =
                client.post("/oauth/token") {
                    headers.append(io.ktor.http.HttpHeaders.Authorization, "Basic $basicCredentials")
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("redirect_uri", redirectUri)
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val token = json["access_token"]?.jsonPrimitive?.content
            assertNotNull(token)
            assertEquals(clientId, provider.getClientIdFromToken(token))
        }
}
