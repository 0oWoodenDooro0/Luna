package luna.mcp

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

object McpWebhooksTable : Table("mcp_webhooks") {
    val id = varchar("id", 64)
    val guildId = varchar("guild_id", 32)
    val channelId = varchar("channel_id", 32)
    val webhookUrl = text("webhook_url")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

data class McpWebhook(
    val id: String,
    val guildId: String,
    val channelId: String,
    val webhookUrl: String,
    val createdAt: Long,
)

class McpWebhookStorage(
    private val database: Database,
) {
    init {
        transaction(database) {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(McpWebhooksTable)
        }
    }

    fun addWebhook(
        guildId: String,
        channelId: String,
        webhookUrl: String,
    ): McpWebhook =
        transaction(database) {
            val existing =
                McpWebhooksTable
                    .selectAll()
                    .where {
                        (McpWebhooksTable.guildId eq guildId) and
                            (McpWebhooksTable.channelId eq channelId) and
                            (McpWebhooksTable.webhookUrl eq webhookUrl)
                    }.singleOrNull()

            if (existing != null) {
                return@transaction rowToWebhook(existing)
            }

            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            McpWebhooksTable.insert {
                it[McpWebhooksTable.id] = id
                it[McpWebhooksTable.guildId] = guildId
                it[McpWebhooksTable.channelId] = channelId
                it[McpWebhooksTable.webhookUrl] = webhookUrl
                it[McpWebhooksTable.createdAt] = now
            }
            McpWebhook(
                id = id,
                guildId = guildId,
                channelId = channelId,
                webhookUrl = webhookUrl,
                createdAt = now,
            )
        }

    fun removeWebhook(
        guildId: String,
        channelId: String,
        webhookUrl: String? = null,
    ): Boolean =
        transaction(database) {
            val deletedCount =
                if (webhookUrl == null) {
                    McpWebhooksTable.deleteWhere {
                        (McpWebhooksTable.guildId eq guildId) and
                            (McpWebhooksTable.channelId eq channelId)
                    }
                } else {
                    McpWebhooksTable.deleteWhere {
                        (McpWebhooksTable.guildId eq guildId) and
                            (McpWebhooksTable.channelId eq channelId) and
                            (McpWebhooksTable.webhookUrl eq webhookUrl)
                    }
                }
            deletedCount > 0
        }

    fun getAllWebhooks(): List<McpWebhook> =
        transaction(database) {
            McpWebhooksTable
                .selectAll()
                .orderBy(McpWebhooksTable.createdAt)
                .map { rowToWebhook(it) }
        }

    fun getWebhooksByChannel(
        guildId: String,
        channelId: String,
    ): List<McpWebhook> =
        transaction(database) {
            McpWebhooksTable
                .selectAll()
                .where {
                    (McpWebhooksTable.guildId eq guildId) and
                        (McpWebhooksTable.channelId eq channelId)
                }.orderBy(McpWebhooksTable.createdAt)
                .map { rowToWebhook(it) }
        }

    private fun rowToWebhook(row: ResultRow): McpWebhook =
        McpWebhook(
            id = row[McpWebhooksTable.id],
            guildId = row[McpWebhooksTable.guildId],
            channelId = row[McpWebhooksTable.channelId],
            webhookUrl = row[McpWebhooksTable.webhookUrl],
            createdAt = row[McpWebhooksTable.createdAt],
        )
}
