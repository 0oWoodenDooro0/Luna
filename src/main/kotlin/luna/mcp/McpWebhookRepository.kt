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
import java.io.File

object McpWebhooksTable : Table("mcp_webhooks") {
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

class McpWebhookRepository(
    private val database: Database,
) {
    init {
        transaction(database) {
            if (db.vendor.contains("sqlite", ignoreCase = true)) {
                exec("PRAGMA journal_mode=WAL;")
                exec("PRAGMA busy_timeout=5000;")
            }
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(McpWebhooksTable, McpClientsTable)
        }
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
        fun create(dbPath: String = "data/urls.db"): McpWebhookRepository {
            val file = File(dbPath)
            file.parentFile?.mkdirs()
            val database =
                Database.connect(
                    "jdbc:sqlite:$dbPath",
                    driver = "org.sqlite.JDBC",
                    setupConnection = { connection ->
                        val meta = connection.metaData
                        val isSqlite = meta.databaseProductName.contains("SQLite", ignoreCase = true) || (meta.url?.startsWith("jdbc:sqlite") == true)
                        if (isSqlite) {
                            connection.createStatement().use { stmt ->
                                stmt.execute("PRAGMA journal_mode=WAL;")
                                stmt.execute("PRAGMA busy_timeout=5000;")
                            }
                        }
                    },
                )
            return McpWebhookRepository(database)
        }
    }
}
