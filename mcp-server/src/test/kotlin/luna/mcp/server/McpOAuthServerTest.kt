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

    @BeforeTest
    fun setUp() {
        val dbName = "test_oauth_${UUID.randomUUID()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        repository = McpWebhookRepository(database)
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
            assertEquals(listOf("authorization_code"), grantTypes)

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
            val authConfig = McpOAuthConfig(clientId = "test-client-id")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
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
            val authConfig = McpOAuthConfig(clientId = "test-client-id")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
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
            val authConfig = McpOAuthConfig(clientId = "test-client-id")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
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
            val authConfig = McpOAuthConfig(clientId = "expected-client-id")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
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
            val authConfig = McpOAuthConfig(clientId = "test-client-id")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
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
            val authConfig = McpOAuthConfig(clientId = "test-client-id")
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
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
            val authConfig =
                McpOAuthConfig(
                    clientId = "test-client-id",
                    clientSecret = "test-secret",
                )
            val provider = McpOAuthProvider(authConfig)
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
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
            val authConfig =
                McpOAuthConfig(
                    clientId = "test-client-id",
                    clientSecret = "test-secret",
                )
            val provider = McpOAuthProvider(authConfig)
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
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
            val authConfig =
                McpOAuthConfig(
                    clientId = "test-client-id",
                    clientSecret = "correct-secret",
                )
            val provider = McpOAuthProvider(authConfig)
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
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
            val authConfig =
                McpOAuthConfig(
                    clientId = "test-client-id",
                    clientSecret = "secret",
                )
            val provider = McpOAuthProvider(authConfig)
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
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
            val authConfig =
                McpOAuthConfig(
                    clientId = "test-client-id",
                )
            val provider = McpOAuthProvider(authConfig)
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
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
            val authConfig =
                McpOAuthConfig(
                    clientId = "test-client-id",
                )
            val provider = McpOAuthProvider(authConfig)
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
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
            val authConfig = McpOAuthConfig(clientId = "test-client-id")
            val provider = McpOAuthProvider(authConfig)
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
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
    fun `GET mcp sse when auth enabled rejects unauthenticated request with 401 and WWW-Authenticate header`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig(requireAuth = true)
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig)
            }

            val response = client.get("/mcp/sse")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("""Bearer error="invalid_token"""", response.headers[io.ktor.http.HttpHeaders.WWWAuthenticate])
        }

    @Test
    fun `GET mcp sse when auth enabled accepts valid Bearer token in Authorization header`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig(requireAuth = true)
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
    fun `GET mcp sse when auth enabled accepts valid Bearer token in query parameter`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig(requireAuth = true)
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
    fun `POST mcp messages when auth enabled rejects unauthenticated request with 401 and WWW-Authenticate header`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig(requireAuth = true)
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
    fun `POST mcp messages when auth enabled rejects invalid Bearer token with 401`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig(requireAuth = true)
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
    fun `POST mcp messages when auth enabled accepts valid Bearer token in Authorization header`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig(requireAuth = true)
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
    fun `POST mcp messages accepts static auth token when configured`() =
        testApplication {
            val broadcaster = DiscordWebhookBroadcaster(repository)
            val authConfig = McpOAuthConfig(staticAuthToken = "secret-static-token")
            val provider = McpOAuthProvider(authConfig)
            application {
                mcpServerModule(broadcaster = broadcaster, authConfig = authConfig, oAuthProvider = provider)
            }

            val request = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}"""
            val response =
                client.post("/mcp/messages?token=secret-static-token") {
                    contentType(io.ktor.http.ContentType.Application.Json)
                    setBody(request)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content)
        }
}
