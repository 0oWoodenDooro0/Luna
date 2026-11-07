package luna.mcp

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File

object McpClientsTable : Table("mcp_clients") {
    val clientId = varchar("client_id", 64)
    val userId = varchar("user_id", 32).uniqueIndex()
    val clientName = varchar("client_name", 64)
    val secretHash = varchar("secret_hash", 128)
    val secretSalt = varchar("secret_salt", 64)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(clientId)
}

data class McpClientRecord(
    val clientId: String,
    val userId: String,
    val clientName: String,
    val secretHash: String,
    val secretSalt: String,
    val createdAt: Long,
    val updatedAt: Long,
)

class McpClientRepository(
    private val database: Database,
) {
    init {
        transaction(database) {
            if (db.vendor.contains("sqlite", ignoreCase = true)) {
                exec("PRAGMA journal_mode=WAL;")
                exec("PRAGMA busy_timeout=5000;")
            }
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(McpClientsTable)
        }
    }

    fun createClient(record: McpClientRecord) {
        transaction(database) {
            McpClientsTable.insert {
                it[clientId] = record.clientId
                it[userId] = record.userId
                it[clientName] = record.clientName
                it[secretHash] = record.secretHash
                it[secretSalt] = record.secretSalt
                it[createdAt] = record.createdAt
                it[updatedAt] = record.updatedAt
            }
        }
    }

    fun findByClientId(clientId: String): McpClientRecord? =
        transaction(database) {
            McpClientsTable
                .selectAll()
                .where { McpClientsTable.clientId eq clientId }
                .singleOrNull()
                ?.let { rowToClient(it) }
        }

    fun findByUserId(userId: String): McpClientRecord? =
        transaction(database) {
            McpClientsTable
                .selectAll()
                .where { McpClientsTable.userId eq userId }
                .singleOrNull()
                ?.let { rowToClient(it) }
        }

    fun updateSecret(
        clientId: String,
        newHash: String,
        newSalt: String,
        updatedAt: Long,
    ): Boolean =
        transaction(database) {
            val updated =
                McpClientsTable.update({ McpClientsTable.clientId eq clientId }) {
                    it[secretHash] = newHash
                    it[secretSalt] = newSalt
                    it[McpClientsTable.updatedAt] = updatedAt
                }
            updated > 0
        }

    fun deleteClient(clientId: String): Boolean =
        transaction(database) {
            val deleted =
                McpClientsTable.deleteWhere {
                    McpClientsTable.clientId eq clientId
                }
            deleted > 0
        }

    private fun rowToClient(row: ResultRow): McpClientRecord =
        McpClientRecord(
            clientId = row[McpClientsTable.clientId],
            userId = row[McpClientsTable.userId],
            clientName = row[McpClientsTable.clientName],
            secretHash = row[McpClientsTable.secretHash],
            secretSalt = row[McpClientsTable.secretSalt],
            createdAt = row[McpClientsTable.createdAt],
            updatedAt = row[McpClientsTable.updatedAt],
        )

    companion object {
        fun create(dbPath: String = "data/urls.db"): McpClientRepository {
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
            return McpClientRepository(database)
        }
    }
}
