package luna.mcp.data

import org.jetbrains.exposed.v1.core.Table

internal object McpClientsTable : Table("mcp_clients") {
    val clientId = varchar("client_id", 64)
    val userId = varchar("user_id", 32).uniqueIndex()
    val clientName = varchar("client_name", 64)
    val secretHash = varchar("secret_hash", 128)
    val secretSalt = varchar("secret_salt", 64)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(clientId)
}

internal object McpWebhooksTable : Table("mcp_webhooks") {
    val id = varchar("id", 64)
    val clientId = varchar("client_id", 64).index()
    val tag = varchar("tag", 32).default("default")
    val guildId = varchar("guild_id", 32)
    val channelId = varchar("channel_id", 32)
    val webhookUrl = text("webhook_url")
    val isAutoProvisioned = bool("is_auto_provisioned").default(true)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(clientId, tag)
    }
}
