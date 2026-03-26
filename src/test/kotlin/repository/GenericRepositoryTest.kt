package website.woodendoor.repository

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.test.*

object TestTable : Table("test_table") {
    val id = varchar("id", 50)
    val name = varchar("name", 50)
    override val primaryKey = PrimaryKey(id)
}

data class TestEntity(val id: String, val name: String)

class GenericRepositoryTest {
    private val testDbFile = "generic_test.db"

    @BeforeTest
    fun setup() {
        Database.connect("jdbc:sqlite:$testDbFile", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.drop(TestTable)
            SchemaUtils.create(TestTable)
        }
    }

    @AfterTest
    fun cleanup() {
        File(testDbFile).delete()
    }

    @Test
    fun `test generic CRUD operations`() {
        val repo: IRepository<TestEntity, String> = object : ExposedRepository<TestEntity, String, TestTable>(TestTable) {
            override fun idColumn(): Column<String> = TestTable.id
            override fun toEntity(row: ResultRow): TestEntity = TestEntity(row[TestTable.id], row[TestTable.name])
            override fun fromEntity(it: UpdateBuilder<*>, entity: TestEntity) {
                it[TestTable.id] = entity.id
                it[TestTable.name] = entity.name
            }
        }
        
        val entity = TestEntity("1", "Test")
        repo.create(entity)
        assertEquals(entity, repo.getById("1"))
        
        val updated = entity.copy(name = "Updated")
        repo.update("1", updated)
        assertEquals(updated, repo.getById("1"))
        
        val all = repo.getAll()
        assertEquals(1, all.size)
        assertEquals(updated, all[0])
        
        repo.delete("1")
        assertNull(repo.getById("1"))
    }
}
