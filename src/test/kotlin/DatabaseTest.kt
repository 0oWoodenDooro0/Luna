package website.woodendoor.repository

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseTest {

    @BeforeEach
    fun setup() {
        Database.connect("jdbc:sqlite::memory:", driver = "org.sqlite.JDBC")
    }

    @AfterEach
    fun teardown() {
    }

    @Test
    fun testPlayersTableExists() {
        transaction {
            SchemaUtils.create(PlayersTable)
            assertTrue(SchemaUtils.listTables().any { it.equals(PlayersTable.tableName, ignoreCase = true) })
        }
    }

    @Test
    fun testInsertAndFetchPlayer() {
        transaction {
            SchemaUtils.create(PlayersTable)
            PlayersTable.insertPlayer(
                id = "user123",
                hp = 100,
                maxHp = 100,
                atk = 10,
                def = 5,
                spd = 8,
                wood = 0,
                stone = 0,
                metal = 0,
                floor = 1
            )

            val player = PlayersTable.fetchPlayer("user123")
            assertTrue(player != null)
            assertEquals("user123", player!!.id)
            assertEquals(100, player!!.attributes.hp)
            assertEquals(100, player!!.attributes.maxHp)
            assertEquals(10, player!!.attributes.atk)
            assertEquals(5, player!!.attributes.def)
            assertEquals(8, player!!.attributes.spd)
        }
    }
}
