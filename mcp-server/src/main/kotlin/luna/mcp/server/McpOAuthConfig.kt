package luna.mcp.server

data class McpOAuthConfig(
    val publicBaseUrl: String = (System.getenv("MCP_PUBLIC_BASE_URL") ?: "https://woodendoor.website").trimEnd('/'),
    val allowedRedirectUris: Set<String> = emptySet(),
    val tokenExpirationSeconds: Long = 3600,
) {
    val normalizedBaseUrl: String = publicBaseUrl.trimEnd('/')

    fun toServerConfig(
        port: Int = McpServerConfig.DEFAULT_PORT,
        dbPath: String = McpServerConfig.DEFAULT_DB_PATH,
    ): McpServerConfig =
        McpServerConfig(
            port = port,
            dbPath = dbPath,
            publicBaseUrl = normalizedBaseUrl,
        )

    companion object {
        fun fromServerConfig(serverConfig: McpServerConfig): McpOAuthConfig =
            McpOAuthConfig(
                publicBaseUrl = serverConfig.publicBaseUrl,
            )
    }
}
