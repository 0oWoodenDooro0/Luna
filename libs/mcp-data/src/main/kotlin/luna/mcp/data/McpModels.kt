package luna.mcp.data

data class McpClientRecord(
    val clientId: String,
    val userId: String,
    val clientName: String,
    val secretHash: String,
    val secretSalt: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class McpWebhookRecord(
    val id: String,
    val clientId: String,
    val tag: String = "default",
    val guildId: String,
    val channelId: String,
    val webhookUrl: String,
    val isAutoProvisioned: Boolean = true,
    val createdAt: Long,
)
