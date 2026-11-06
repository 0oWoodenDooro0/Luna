package luna.mcp.server

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

object McpWebhooksTable : Table("mcp_webhooks") {
    val id = varchar("id", 64)
    val guildId = varchar("guild_id", 32)
    val channelId = varchar("channel_id", 32)
    val webhookUrl = text("webhook_url")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

data class McpWebhookRecord(
    val id: String,
    val guildId: String,
    val channelId: String,
    val webhookUrl: String,
    val createdAt: Long,
)

class McpWebhookRepository(
    private val database: Database,
) {
    init {
        transaction(database) {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(McpWebhooksTable)
        }
    }

    fun getAllWebhooks(): List<McpWebhookRecord> =
        transaction(database) {
            McpWebhooksTable
                .selectAll()
                .orderBy(McpWebhooksTable.createdAt)
                .map { rowToWebhook(it) }
        }

    private fun rowToWebhook(row: ResultRow): McpWebhookRecord =
        McpWebhookRecord(
            id = row[McpWebhooksTable.id],
            guildId = row[McpWebhooksTable.guildId],
            channelId = row[McpWebhooksTable.channelId],
            webhookUrl = row[McpWebhooksTable.webhookUrl],
            createdAt = row[McpWebhooksTable.createdAt],
        )

    companion object {
        fun create(dbPath: String = "./data/urls.db"): McpWebhookRepository {
            val file = File(dbPath)
            file.parentFile?.mkdirs()
            val database = Database.connect("jdbc:sqlite:$dbPath", driver = "org.sqlite.JDBC")
            return McpWebhookRepository(database)
        }
    }
}
