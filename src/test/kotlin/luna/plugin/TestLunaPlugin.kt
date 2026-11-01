package luna.plugin

import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object TestPluginItemsTable : Table("test_plugin_items") {
    val id = varchar("id", 64)
    val name = varchar("name", 128)

    override val primaryKey = PrimaryKey(id)
}

class TestLunaPlugin : LunaPlugin {
    override val name: String = "test-plugin"

    override suspend fun initialize(context: PluginContext) {
        initialized = true
        recordedConfigValue = context.config<String>("test.greeting")

        // Exercise database capability
        transaction(context.database) {
            SchemaUtils.create(TestPluginItemsTable)
            TestPluginItemsTable.insert {
                it[id] = "item-1"
                it[name] = "Test Item"
            }
        }

        // Exercise Ktor routing capability
        context.routing.get("/api/test-plugin/hello") {
            call.respondText(recordedConfigValue ?: "Default Hello", ContentType.Text.Plain)
        }
    }

    override suspend fun start() {
        started = true
    }

    override suspend fun shutdown() {
        shutdown = true
    }

    companion object {
        var initialized: Boolean = false
        var started: Boolean = false
        var shutdown: Boolean = false
        var recordedConfigValue: String? = null

        fun reset() {
            initialized = false
            started = false
            shutdown = false
            recordedConfigValue = null
        }
    }
}
