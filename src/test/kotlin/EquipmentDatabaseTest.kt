package website.woodendoor.repository

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EquipmentDatabaseTest {

    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
    }

    @Test
    fun testEquipmentColumnsExist() {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(PlayersTable)
            
            PlayersTable.insertPlayer(
                id = "tester",
                hp = 100,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8,
                wood = 10,
                stone = 10,
                metal = 10,
                floor = 1,
                weaponLevel = 1,
                shieldLevel = 2,
                armorLevel = 3
            )

            val player = PlayersTable.fetchPlayer("tester")
            assertTrue(player != null)
            assertEquals(1, player!!.weaponLevel)
            assertEquals(2, player!!.shieldLevel)
            assertEquals(3, player!!.armorLevel)
        }
    }
}
