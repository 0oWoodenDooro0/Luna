package luna.mcp.data

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class McpWebhookRepository(
    private val database: Database,
) {
    init {
        McpDatabase.initSchema(database)
    }

    fun addWebhook(record: McpWebhookRecord) {
        transaction(database) {
            McpWebhooksTable.insert {
                it[id] = record.id
                it[clientId] = record.clientId
                it[tag] = record.tag
                it[guildId] = record.guildId
                it[channelId] = record.channelId
                it[webhookUrl] = record.webhookUrl
                it[isAutoProvisioned] = record.isAutoProvisioned
                it[createdAt] = record.createdAt
            }
        }
    }

    fun findByClientAndTag(
        clientId: String,
        tag: String,
    ): McpWebhookRecord? =
        transaction(database) {
            McpWebhooksTable
                .selectAll()
                .where {
                    (McpWebhooksTable.clientId eq clientId) and
                        (McpWebhooksTable.tag eq tag)
                }.singleOrNull()
                ?.let { rowToWebhook(it) }
        }

    fun listByClientId(clientId: String): List<McpWebhookRecord> =
        transaction(database) {
            McpWebhooksTable
                .selectAll()
                .where { McpWebhooksTable.clientId eq clientId }
                .orderBy(McpWebhooksTable.createdAt)
                .map { rowToWebhook(it) }
        }

    fun removeByTag(
        clientId: String,
        tag: String,
    ): Boolean =
        transaction(database) {
            val deleted =
                McpWebhooksTable.deleteWhere {
                    (McpWebhooksTable.clientId eq clientId) and
                        (McpWebhooksTable.tag eq tag)
                }
            deleted > 0
        }

    fun deleteByClientId(clientId: String): Int =
        transaction(database) {
            McpWebhooksTable.deleteWhere {
                McpWebhooksTable.clientId eq clientId
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
            clientId = row[McpWebhooksTable.clientId],
            tag = row[McpWebhooksTable.tag],
            guildId = row[McpWebhooksTable.guildId],
            channelId = row[McpWebhooksTable.channelId],
            webhookUrl = row[McpWebhooksTable.webhookUrl],
            isAutoProvisioned = row[McpWebhooksTable.isAutoProvisioned],
            createdAt = row[McpWebhooksTable.createdAt],
        )

    companion object {
        fun create(dbPath: String = ".data/luna.db"): McpWebhookRepository {
            val database = McpDatabase.connect(dbPath)
            return McpWebhookRepository(database)
        }
    }
}
