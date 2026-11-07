package luna.mcp.data

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

object McpDatabase {
    fun connect(dbPath: String = ".data/luna.db"): Database {
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

        initSchema(database)
        return database
    }

    fun initSchema(database: Database) {
        transaction(database) {
            if (db.vendor.contains("sqlite", ignoreCase = true)) {
                exec("PRAGMA journal_mode=WAL;")
                exec("PRAGMA busy_timeout=5000;")
            }
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(McpClientsTable, McpWebhooksTable)
        }
    }
}
