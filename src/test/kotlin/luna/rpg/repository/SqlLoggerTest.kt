package luna.rpg.repository

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlLoggerTest {
    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    object TestTable : Table("test_table") {
        val id = integer("id").autoIncrement()
        override val primaryKey = PrimaryKey(id)
    }

    @BeforeEach
    fun setup() {
        logger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        listAppender = ListAppender()
        listAppender.start()
        logger.addAppender(listAppender)
        
        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(listAppender)
    }

    @Test
    fun `should log SQL queries to JSON_LOGGER`() {
        transaction {
            addLogger(SqlJsonLogger())
            SchemaUtils.create(TestTable)
            TestTable.insert {
                it[id] = 1
            }
        }

        val logEntries = listAppender.list
        assertTrue(logEntries.isNotEmpty(), "Should have logged SQL queries")
    }
}
