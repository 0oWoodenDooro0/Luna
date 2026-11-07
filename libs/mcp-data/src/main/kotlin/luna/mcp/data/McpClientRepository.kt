package luna.mcp.data

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class McpClientRepository(
    val database: Database,
) {
    init {
        McpDatabase.initSchema(database)
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
        fun create(dbPath: String = ".data/urls.db"): McpClientRepository {
            val database = McpDatabase.connect(dbPath)
            return McpClientRepository(database)
        }
    }
}
