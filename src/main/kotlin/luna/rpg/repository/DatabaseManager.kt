package luna.rpg.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

object DatabaseManager {
    fun init(url: String = "jdbc:sqlite:data/rpg.db") {
        if (url.contains("data/rpg.db")) {
            // Ensure the data directory exists
            val dbFile = File("data/rpg.db")
            if (!dbFile.parentFile.exists()) {
                dbFile.parentFile.mkdirs()
            }
        }

        Database.connect(url, driver = "org.sqlite.JDBC")

        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable, PlayerMapsTable)
        }
    }
}
