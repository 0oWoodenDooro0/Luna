package luna.plugin

import dev.kord.core.Kord
import io.ktor.server.routing.Routing
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginContextTest {
    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    private fun createContext(configMap: Map<String, Any?> = emptyMap()): DefaultPluginContext =
        DefaultPluginContext(
            kord = mockk<Kord>(relaxed = true),
            routing = mockk<Routing>(relaxed = true),
            database = createTestDb(),
            configMap = configMap,
        )

    @Test
    fun `resolves exact key when present in config map`() {
        val context = createContext(mapOf("curtly.baseUrl" to "https://exact.example.com/s/"))

        assertEquals("https://exact.example.com/s/", context.config("curtly.baseUrl", "default"))
        assertEquals("https://exact.example.com/s/", context.config("curtly.baseUrl", String::class.java))
    }

    @Test
    fun `resolves uppercase snake case key when exact dotted key is absent`() {
        val context = createContext(mapOf("CURTLY_BASE_URL" to "https://upper.example.com/s/"))

        assertEquals("https://upper.example.com/s/", context.config("curtly.baseUrl", "default"))
        assertEquals("https://upper.example.com/s/", context.config<String>("curtly.baseUrl"))
    }

    @Test
    fun `resolves lowercase dotted snake case key when exact key is absent`() {
        val context = createContext(mapOf("curtly.base_url" to "https://lower-snake.example.com/s/"))

        assertEquals("https://lower-snake.example.com/s/", context.config("curtly.baseUrl", "default"))
    }

    @Test
    fun `resolves plugins prefix uppercase key when exact and direct upper snake are absent`() {
        val context = createContext(mapOf("PLUGINS_CURTLY_BASE_URL" to "https://plugins.example.com/s/"))

        assertEquals("https://plugins.example.com/s/", context.config("curtly.baseUrl", "default"))
        assertEquals("https://plugins.example.com/s/", context.config<String>("curtly.baseUrl"))
    }

    @Test
    fun `resolves simple property name uppercase when namespace prefix is absent`() {
        val context = createContext(mapOf("BASE_URL" to "https://global.example.com/s/"))

        assertEquals("https://global.example.com/s/", context.config("curtly.baseUrl", "default"))
    }

    @Test
    fun `resolves summarizer alias to summary uppercase key`() {
        val context = createContext(mapOf("SUMMARY_EMOJI" to "✨"))

        assertEquals("✨", context.config("summarizer.emoji", "📝"))
        assertEquals("✨", context.config("summary.emoji", "📝"))
        assertEquals("✨", context.config<String>("summarizer.emoji"))
    }

    @Test
    fun `respects precedence order from exact key down to simple property`() {
        val allConfig =
            mapOf(
                "curtly.baseUrl" to "https://1-exact.com/s/",
                "CURTLY_BASE_URL" to "https://2-upper.com/s/",
                "PLUGINS_CURTLY_BASE_URL" to "https://3-plugins.com/s/",
                "BASE_URL" to "https://4-simple.com/s/",
            )

        val context1 = createContext(allConfig)
        assertEquals("https://1-exact.com/s/", context1.config("curtly.baseUrl", "default"))

        val withoutExact = allConfig - "curtly.baseUrl"
        val context2 = createContext(withoutExact)
        assertEquals("https://2-upper.com/s/", context2.config("curtly.baseUrl", "default"))

        val withoutUpper = withoutExact - "CURTLY_BASE_URL"
        val context3 = createContext(withoutUpper)
        assertEquals("https://3-plugins.com/s/", context3.config("curtly.baseUrl", "default"))

        val withoutPlugins = withoutUpper - "PLUGINS_CURTLY_BASE_URL"
        val context4 = createContext(withoutPlugins)
        assertEquals("https://4-simple.com/s/", context4.config("curtly.baseUrl", "default"))
    }

    @Test
    fun `converts typed values safely across supported primitive wrapper types`() {
        val context =
            createContext(
                mapOf(
                    "server.port" to "8080",
                    "server.timeoutMs" to "5000",
                    "server.ratio" to "0.75",
                    "server.enabled" to "true",
                    "server.name" to 12345,
                ),
            )

        assertEquals(8080, context.config("server.port", 3000))
        assertEquals(5000L, context.config("server.timeoutMs", 1000L))
        assertEquals(0.75, context.config("server.ratio", 1.0))
        assertEquals(true, context.config("server.enabled", false))
        assertEquals("12345", context.config("server.name", "default"))

        // Reified without default
        assertEquals(8080, context.config<Int>("server.port"))
        assertEquals(5000L, context.config<Long>("server.timeoutMs"))
        assertEquals(0.75, context.config<Double>("server.ratio"))
        assertEquals(true, context.config<Boolean>("server.enabled"))
    }

    @Test
    fun `falls back to default value when key does not exist`() {
        val context = createContext(emptyMap())

        assertEquals("fallback", context.config("nonexistent.str", "fallback"))
        assertEquals(42, context.config("nonexistent.int", 42))
        assertEquals(100L, context.config("nonexistent.long", 100L))
        assertEquals(3.14, context.config("nonexistent.double", 3.14))
        assertEquals(true, context.config("nonexistent.bool", true))

        assertNull(context.config<String>("nonexistent.str"))
    }

    @Test
    fun `falls back to default value when key is blank or whitespace only`() {
        val context = createContext(mapOf("" to "empty", "   " to "blank", "valid.key" to "value"))

        assertEquals("default", context.config("", "default"))
        assertEquals("default", context.config("   ", "default"))
        assertEquals("default", context.config("\t\n", "default"))

        assertNull(context.config<String>(""))
        assertNull(context.config<String>("   "))
    }

    @Test
    fun `falls back to default value when integer parsing fails without throwing`() {
        val context = createContext(mapOf("app.port" to "not-a-valid-integer"))

        assertEquals(8080, context.config("app.port", 8080))
        assertNull(context.config<Int>("app.port"))
    }

    @Test
    fun `falls back to default value when long parsing fails without throwing`() {
        val context = createContext(mapOf("app.timeout" to "invalid-long"))

        assertEquals(3000L, context.config("app.timeout", 3000L))
        assertNull(context.config<Long>("app.timeout"))
    }

    @Test
    fun `falls back to default value when double parsing fails without throwing`() {
        val context = createContext(mapOf("app.ratio" to "invalid-double"))

        assertEquals(1.5, context.config("app.ratio", 1.5))
        assertNull(context.config<Double>("app.ratio"))
    }

    @Test
    fun `falls back to default value when boolean parsing fails without throwing`() {
        val context = createContext(mapOf("app.debug" to "not-a-boolean"))

        assertEquals(false, context.config("app.debug", false))
        assertEquals(true, context.config("app.debug", true))
        assertNull(context.config<Boolean>("app.debug"))
    }

    @Test
    fun `falls back to default value when value in map is explicit null`() {
        val context = createContext(mapOf("app.nullProperty" to null))

        assertEquals("default", context.config("app.nullProperty", "default"))
        assertNull(context.config<String>("app.nullProperty"))
    }

    @Test
    fun `supports java primitive type classes directly`() {
        val context =
            createContext(
                mapOf(
                    "intVal" to 42,
                    "longVal" to 999L,
                    "doubleVal" to 2.5,
                    "boolVal" to true,
                ),
            )

        assertEquals(42, context.config("intVal", Int::class.javaPrimitiveType!!))
        assertEquals(999L, context.config("longVal", Long::class.javaPrimitiveType!!))
        assertEquals(2.5, context.config("doubleVal", Double::class.javaPrimitiveType!!))
        assertEquals(true, context.config("boolVal", Boolean::class.javaPrimitiveType!!))
    }

    @Test
    fun `dual namespace lookup works with custom configResolver lambda`() {
        val storage = mapOf("PLUGINS_TEST_URL" to "https://custom-resolver.com")
        val context =
            DefaultPluginContext(
                kord = mockk<Kord>(relaxed = true),
                routing = mockk<Routing>(relaxed = true),
                database = createTestDb(),
                configResolver = { key, _ -> storage[key] },
            )

        assertEquals("https://custom-resolver.com", context.config("test.url", "default"))
    }

    @Test
    fun `plugin context interface provides default config implementation`() {
        val customContext =
            object : PluginContext {
                override val kord: Kord = mockk(relaxed = true)
                override val routing: Routing = mockk(relaxed = true)
                override val database: Database = createTestDb()

                @Suppress("UNCHECKED_CAST")
                override fun <T> config(
                    key: String,
                    clazz: Class<T>,
                ): T? = if (key == "known") "value" as? T else null
            }

        assertEquals("value", customContext.config("known", "fallback"))
        assertEquals("fallback", customContext.config("unknown", "fallback"))
    }
}
