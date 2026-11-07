package luna.mcp.server

import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `GET oauth authorize with dynamic client in DB succeeds and redirects to Google URL`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val now = System.currentTimeMillis()
            val clientRecord =
                McpClientRecord(
                    clientId = "dynamic-client-google",
                    userId = "user-123",
                    clientName = "Dynamic Google Client",
                    secretHash = "dummy-hash",
                    secretSalt = "dummy-salt",
                    createdAt = now,
                    updatedAt = now,
                )
            clientRepository.createClient(clientRecord)

            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    clientRepository = clientRepository,
                )
            }

            val client = createClient { followRedirects = false }
            val redirectUri = "https://oauth-redirect.googleusercontent.com/r/my-project"
            val response =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", "dynamic-client-google")
                        parameters.append("redirect_uri", redirectUri)
                        parameters.append("response_type", "code")
                        parameters.append("state", "testStateDynamic")
                    }
                }

            assertEquals(HttpStatusCode.Found, response.status)
            val location = response.headers["Location"]
            assertNotNull(location)
            assertTrue(location.startsWith(redirectUri))
            assertTrue(location.contains("code="))
            assertTrue(location.contains("state=testStateDynamic"))
        }

    @Test
    fun `GET oauth authorize with unknown client_id returns 400 invalid_client`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    clientRepository = clientRepository,
                )
            }

            val client = createClient { followRedirects = false }
            val response =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", "unknown-client-id")
                        parameters.append("redirect_uri", "https://oauth-redirect.googleusercontent.com/r/my-project")
                        parameters.append("response_type", "code")
                    }
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_client", json["error"]?.jsonPrimitive?.content)
            assertEquals("Unknown or invalid client_id", json["error_description"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET oauth authorize with disallowed redirect URI returns 400 invalid_request`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val now = System.currentTimeMillis()
            val clientRecord =
                McpClientRecord(
                    clientId = "dynamic-client-1",
                    userId = "user-1",
                    clientName = "Client 1",
                    secretHash = "hash1",
                    secretSalt = "salt1",
                    createdAt = now,
                    updatedAt = now,
                )
            clientRepository.createClient(clientRecord)

            application {
                mcpServerModule(
                    broadcaster = broadcaster,
                    clientRepository = clientRepository,
                )
            }

            val client = createClient { followRedirects = false }
            val response =
                client.get("/oauth/authorize") {
                    url {
                        parameters.append("client_id", "dynamic-client-1")
                        parameters.append("redirect_uri", "https://malicious.attacker.com/oauth")
                        parameters.append("response_type", "code")
                    }
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_request", json["error"]?.jsonPrimitive?.content)
            assertEquals("Unauthorized or invalid redirect_uri", json["error_description"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST oauth token with valid secret and PKCE verifier returns access_token tagged with client_id`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val plainSecret = "super-secret-passphrase"
            val salt = "secure-salt-123"
            val hash = McpOAuthSecurity.hashSecret(plainSecret, salt)
            val clientId = "dynamic-client-token-test"
            val now = System.currentTimeMillis()

            val clientRecord =
                McpClientRecord(
                    clientId = clientId,
                    userId = "user-token-test",
                    clientName = "Token Test Client",
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

            val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
            val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
            val redirectUri = "https://oauth-redirect.googleusercontent.com/r/my-project"

            val code =
                provider.issueAuthorizationCode(
                    clientId = clientId,
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
                                append("client_id", clientId)
                                append("client_secret", plainSecret)
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
            assertEquals(clientId, provider.getClientIdFromToken(accessToken))
        }

    @Test
    fun `POST oauth token with wrong secret returns 401 invalid_client`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val plainSecret = "correct-client-secret"
            val salt = "salt-456"
            val hash = McpOAuthSecurity.hashSecret(plainSecret, salt)
            val clientId = "dynamic-client-wrong-secret"
            val now = System.currentTimeMillis()

            val clientRecord =
                McpClientRecord(
                    clientId = clientId,
                    userId = "user-wrong-secret",
                    clientName = "Wrong Secret Test Client",
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

            val response =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", "some-code")
                                append("redirect_uri", "https://oauth-redirect.googleusercontent.com/r/my-project")
                                append("client_id", clientId)
                                append("client_secret", "wrong-secret-value")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_client", json["error"]?.jsonPrimitive?.content)
            assertEquals("Client authentication failed", json["error_description"]?.jsonPrimitive?.content)
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
            assertEquals(listOf("authorization_code", "refresh_token"), grantTypes)

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
                                append("grant_type", "client_credentials")
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
    fun `POST oauth token returns refresh_token and supports grant_type refresh_token`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val clientId = "refresh-test-client"
            val secret = "refresh-secret"
            registerTestClient(clientId, secret)
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

            val code = provider.issueAuthorizationCode(clientId, "https://oauth-redirect.googleusercontent.com/r/test", null, null)

            val tokenResponse =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "authorization_code")
                                append("code", code)
                                append("client_id", clientId)
                                append("client_secret", secret)
                                append("redirect_uri", "https://oauth-redirect.googleusercontent.com/r/test")
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, tokenResponse.status)
            val tokenJson = Json.parseToJsonElement(tokenResponse.bodyAsText()).jsonObject
            val accessToken = tokenJson["access_token"]?.jsonPrimitive?.content
            val refreshToken = tokenJson["refresh_token"]?.jsonPrimitive?.content
            assertNotNull(accessToken)
            assertNotNull(refreshToken)

            val refreshResponse =
                client.post("/oauth/token") {
                    setBody(
                        io.ktor.client.request.forms.FormDataContent(
                            io.ktor.http.parameters {
                                append("grant_type", "refresh_token")
                                append("refresh_token", refreshToken)
                                append("client_id", clientId)
                                append("client_secret", secret)
                            },
                        ),
                    )
                }

            assertEquals(HttpStatusCode.OK, refreshResponse.status)
            val refreshJson = Json.parseToJsonElement(refreshResponse.bodyAsText()).jsonObject
            assertNotNull(refreshJson["access_token"]?.jsonPrimitive?.content)
            assertEquals(refreshToken, refreshJson["refresh_token"]?.jsonPrimitive?.content)
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
}
