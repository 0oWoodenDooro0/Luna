package luna.mcp.server

data class McpOAuthConfig(
    val publicBaseUrl: String = (System.getenv("MCP_PUBLIC_BASE_URL") ?: "https://woodendoor.website").trimEnd('/'),
    val clientId: String = System.getenv("MCP_OAUTH_CLIENT_ID") ?: "luna-mcp-client",
    val clientSecret: String? = System.getenv("MCP_OAUTH_CLIENT_SECRET"),
    val staticAuthToken: String? = System.getenv("MCP_AUTH_TOKEN"),
    val allowedRedirectUris: Set<String> = emptySet(),
    val tokenExpirationSeconds: Long = 3600,
    val requireAuth: Boolean =
        !System.getenv("MCP_OAUTH_CLIENT_SECRET").isNullOrBlank() ||
            !System.getenv("MCP_AUTH_TOKEN").isNullOrBlank(),
) {
    val normalizedBaseUrl: String = publicBaseUrl.trimEnd('/')

    val isAuthEnabled: Boolean
        get() = requireAuth || !clientSecret.isNullOrBlank() || !staticAuthToken.isNullOrBlank()
}
