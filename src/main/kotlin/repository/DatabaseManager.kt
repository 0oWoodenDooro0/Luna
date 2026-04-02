package website.woodendoor.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

object DatabaseManager {
    fun init() {
        // Ensure the data directory exists
        val dbFile = File("data/rpg.db")
        if (!dbFile.parentFile.exists()) {
            dbFile.parentFile.mkdirs()
        }

        Database.connect("jdbc:sqlite:data/rpg.db", driver = "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(PlayersTable)
        }
    }
}
