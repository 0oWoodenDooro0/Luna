package luna.mcp.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

class McpOAuthDiscoveryAndAuthorizeTest {
    private lateinit var database: Database
    private lateinit var repository: McpWebhookRepository
    private lateinit var clientRepository: McpClientRepository

    @BeforeTest
    fun setUp() {
        val dbName = "test_oauth_discovery_${UUID.randomUUID()}"
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
    fun `GET well-known oauth authorization server returns RFC 8414 metadata json`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig(publicBaseUrl = "https://woodendoor.website")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
            }

            val response = client.get("/.well-known/oauth-authorization-server")
            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("https://woodendoor.website", json["issuer"]?.jsonPrimitive?.content)
            assertEquals("https://woodendoor.website/oauth/authorize", json["authorization_endpoint"]?.jsonPrimitive?.content)
            assertEquals("https://woodendoor.website/oauth/token", json["token_endpoint"]?.jsonPrimitive?.content)

            val responseTypes = json["response_types_supported"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertNotNull(responseTypes)
            assertEquals(listOf("code"), responseTypes)

            val grantTypes = json["grant_types_supported"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertNotNull(grantTypes)
            assertEquals(listOf("authorization_code", "refresh_token", "client_credentials"), grantTypes)

            val authMethods = json["token_endpoint_auth_methods_supported"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertNotNull(authMethods)
            assertEquals(listOf("client_secret_basic", "client_secret_post"), authMethods)

            val codeChallengeMethods = json["code_challenge_methods_supported"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertNotNull(codeChallengeMethods)
            assertEquals(listOf("S256", "plain"), codeChallengeMethods)
        }

    @Test
    fun `GET well-known openid configuration returns RFC 8414 metadata json alias`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig(publicBaseUrl = "https://custom.example.com")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
            }

            val response = client.get("/.well-known/openid-configuration")
            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("https://custom.example.com", json["issuer"]?.jsonPrimitive?.content)
            assertEquals("https://custom.example.com/oauth/authorize", json["authorization_endpoint"]?.jsonPrimitive?.content)
            assertEquals("https://custom.example.com/oauth/token", json["token_endpoint"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET well-known oauth protected resource returns RFC 9728 metadata json`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig(publicBaseUrl = "https://woodendoor.website/")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
            }

            val response = client.get("/.well-known/oauth-protected-resource")
            assertEquals(HttpStatusCode.OK, response.status)

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("https://woodendoor.website/mcp", json["resource"]?.jsonPrimitive?.content)
            val authServers = json["authorization_servers"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertNotNull(authServers)
            assertEquals(listOf("https://woodendoor.website"), authServers)
        }

    @Test
    fun `GET oauth authorize with valid Google redirect URI issues code and redirects 302`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id")
            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(broadcaster = broadcaster, clientRepository = clientRepository, oAuthProvider = provider)
            }

            val client = createClient { followRedirects = false }
            val redirectUri = "https://oauth-redirect.googleusercontent.com/r/google-project-123"
            val response =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", "test-client-id")
                        parameters.append("redirect_uri", redirectUri)
                        parameters.append("response_type", "code")
                        parameters.append("state", "xyzState123")
                        parameters.append("code_challenge", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
                        parameters.append("code_challenge_method", "S256")
                    }
                }

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]
            assertNotNull(location)
            assertTrue(location.startsWith(redirectUri))
            assertTrue(location.contains("code="))
            assertTrue(location.contains("state=xyzState123"))
        }

    @Test
    fun `GET oauth authorize with valid localhost redirect URI issues code and redirects 302`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id")
            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(broadcaster = broadcaster, clientRepository = clientRepository, oAuthProvider = provider)
            }

            val client = createClient { followRedirects = false }
            val redirectUri = "http://localhost:8080/callback"
            val response =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", "test-client-id")
                        parameters.append("redirect_uri", redirectUri)
                        parameters.append("response_type", "code")
                        parameters.append("state", "localState")
                    }
                }

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]
            assertNotNull(location)
            assertTrue(location.startsWith(redirectUri))
            assertTrue(location.contains("code="))
            assertTrue(location.contains("state=localState"))
        }

    @Test
    fun `GET oauth authorize with invalid response_type returns 400 Bad Request`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id")
            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(broadcaster = broadcaster, clientRepository = clientRepository, oAuthProvider = provider)
            }

            val client = createClient { followRedirects = false }
            val response =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", "test-client-id")
                        parameters.append("redirect_uri", "http://localhost:8080/callback")
                        parameters.append("response_type", "token")
                    }
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("unsupported_response_type", json["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET oauth authorize with mismatched client_id returns 400 Bad Request`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("expected-client-id")
            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(broadcaster = broadcaster, clientRepository = clientRepository, oAuthProvider = provider)
            }

            val client = createClient { followRedirects = false }
            val response =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", "wrong-client-id")
                        parameters.append("redirect_uri", "http://localhost:8080/callback")
                        parameters.append("response_type", "code")
                    }
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_client", json["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET oauth authorize with unauthorized redirect_uri returns 400 Bad Request`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id")
            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(broadcaster = broadcaster, clientRepository = clientRepository, oAuthProvider = provider)
            }

            val client = createClient { followRedirects = false }
            val response =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", "test-client-id")
                        parameters.append("redirect_uri", "https://malicious.attacker.com/oauth")
                        parameters.append("response_type", "code")
                    }
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_request", json["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET oauth authorize appends parameters properly when redirect_uri contains query parameters`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            registerTestClient("test-client-id")
            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(broadcaster = broadcaster, clientRepository = clientRepository, oAuthProvider = provider)
            }

            val client = createClient { followRedirects = false }
            val redirectUri = "https://oauth-redirect.googleusercontent.com/a/my-app?source=test"
            val response =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", "test-client-id")
                        parameters.append("redirect_uri", redirectUri)
                        parameters.append("response_type", "code")
                        parameters.append("state", "stateWithParams")
                    }
                }

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]
            assertNotNull(location)
            assertTrue(location.startsWith("https://oauth-redirect.googleusercontent.com/a/my-app?source=test&code="))
            assertTrue(location.contains("&state=stateWithParams"))
        }

    @Test
    fun `GET oauth authorize with client_id containing whitespace trims and redirects 302`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val clientId = "whitespace-client-id"
            registerTestClient(clientId)

            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(broadcaster = broadcaster, clientRepository = clientRepository, oAuthProvider = provider)
            }

            val client = createClient { followRedirects = false }
            val redirectUri = "https://oauth-redirect.googleusercontent.com/r/my-project"
            val response =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", "  whitespace-client-id  ")
                        parameters.append("redirect_uri", redirectUri)
                        parameters.append("response_type", "code")
                    }
                }

            assertEquals(HttpStatusCode.Found, response.status)
        }

    @Test
    fun `GET oauth authorize with Google sandbox and Vertex AI redirect URIs succeeds`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val clientId = "sandbox-vertex-client"
            registerTestClient(clientId)

            val provider = McpOAuthProvider(clientRepository = clientRepository)
            application {
                mcpServerModule(broadcaster = broadcaster, clientRepository = clientRepository, oAuthProvider = provider)
            }

            val client = createClient { followRedirects = false }
            val sandboxUri = "https://oauth-redirect-sandbox.googleusercontent.com/r/sandbox-proj"
            val vertexUri = "https://vertexaisearch.cloud.google.com/oauth-redirect"

            val responseSandbox =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", clientId)
                        parameters.append("redirect_uri", sandboxUri)
                        parameters.append("response_type", "code")
                    }
                }
            assertEquals(HttpStatusCode.Found, responseSandbox.status)
            assertTrue(responseSandbox.headers["Location"]!!.startsWith(sandboxUri))

            val responseVertex =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", clientId)
                        parameters.append("redirect_uri", vertexUri)
                        parameters.append("response_type", "code")
                    }
                }
            assertEquals(HttpStatusCode.Found, responseVertex.status)
            assertTrue(responseVertex.headers["Location"]!!.startsWith(vertexUri))
        }
}
