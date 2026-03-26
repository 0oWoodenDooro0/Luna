package website.woodendoor

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object Players : Table() {
    val userId = varchar("user_id", 30)
    val level = integer("level").default(1)
    val xp = integer("xp").default(0)
    val gold = integer("gold").default(0)
    val lastHuntTime = long("last_hunt_time").default(0L)

    override val primaryKey = PrimaryKey(userId)
}

fun initDatabase() {
    Database.connect("jdbc:sqlite:rpg_data.db", driver = "org.sqlite.JDBC")

    transaction {
        SchemaUtils.createMissingTablesAndColumns(Players)
    }
}
