package luna.rpg

import luna.rpg.repository.PlayersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RecoveryDatabaseTest {
    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
    }

    @Test
    fun testRecoveryColumnsExist() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            PlayersTable.insertPlayer(
                id = "recovery_user",
                hp = 0,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8,
                wood = 0,
                stone = 0,
                metal = 0,
                floor = 1,
                recoveryStartAt = 123456789L,
                recoveryLevel = 1,
            )

            val result = PlayersTable.selectAll().where { PlayersTable.id eq "recovery_user" }.single()
            assertEquals(123456789L, result[PlayersTable.recoveryStartAt])
            assertEquals(1, result[PlayersTable.recoveryLevel])
        }
    }
}
