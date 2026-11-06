package luna.mcp.server

/**
 * Data transfer object encapsulating parameters for an OAuth 2.1 token exchange request.
 */
data class OAuthTokenRequest(
    val grantType: String?,
    val code: String?,
    val redirectUri: String?,
    val clientId: String?,
    val clientSecret: String?,
    val codeVerifier: String?,
    val refreshToken: String? = null,
)
