package luna.plugin

import dev.kord.core.Kord
import io.ktor.server.routing.Routing
import io.mockk.mockk
import luna.config.ConfigValidationException
import luna.config.EnvReader
import luna.core.ai.AiTraceConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class PluginContextTest {
    private fun createTestDb(): Database = mockk(relaxed = true)

    private fun createContext(env: EnvReader = EnvReader.of()): DefaultPluginContext =
        DefaultPluginContext(
            kord = mockk<Kord>(relaxed = true),
            routing = mockk<Routing>(relaxed = true),
            database = createTestDb(),
            env = env,
        )

    @Test
    fun `DefaultPluginContext defaults to empty EnvReader and exposes typed env accessors`() {
        val defaultContext = createContext()
        assertEquals(8080, defaultContext.env.port("PORT", 8080))
        assertEquals(false, defaultContext.env.boolean("CHATTER_ENABLED", false))

        val configuredContext =
            createContext(
                EnvReader.of(
                    mapOf(
                        "PORT" to "9090",
                        "BASE_URL" to "https://example.com",
                        "CHATTER_ENABLED" to "true",
                    ),
                ),
            )
        assertEquals(9090, configuredContext.env.port("PORT", 8080))
        assertEquals("https://example.com", configuredContext.env.string("BASE_URL"))
        assertEquals(true, configuredContext.env.boolean("CHATTER_ENABLED", false))
    }

    @Test
    fun `AiTraceConfig from EnvReader resolves canonical keys and fails fast on non-positive bounds`() {
        val defaults = AiTraceConfig.from(createContext().env)
        assertEquals(true, defaults.enabled)
        assertEquals("data/logs/ai", defaults.dir)
        assertEquals(14, defaults.maxDays)
        assertEquals(200, defaults.maxTotalMb)

        val configured =
            AiTraceConfig.from(
                createContext(
                    EnvReader.of(
                        mapOf(
                            "AI_TRACE_ENABLED" to "false",
                            "AI_TRACE_DIR" to "/tmp/ai-trace",
                            "AI_TRACE_MAX_DAYS" to "7",
                            "AI_TRACE_MAX_TOTAL_MB" to "64",
                        ),
                    ),
                ).env,
            )
        assertEquals(false, configured.enabled)
        assertEquals("/tmp/ai-trace", configured.dir)
        assertEquals(7, configured.maxDays)
        assertEquals(64, configured.maxTotalMb)

        assertThrows<ConfigValidationException> {
            AiTraceConfig.from(EnvReader.of(mapOf("AI_TRACE_MAX_DAYS" to "0")))
        }
        assertThrows<ConfigValidationException> {
            AiTraceConfig.from(EnvReader.of(mapOf("AI_TRACE_MAX_TOTAL_MB" to "-5")))
        }
    }
}
