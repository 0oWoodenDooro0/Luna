package luna.core

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class LoggingInfrastructureTest {
    @Test
    fun `should have logback classes available`() {
        val loggerContext = Class.forName("ch.qos.logback.classic.LoggerContext")
        assertNotNull(loggerContext)
    }

    @Test
    fun `should have logstash encoder available`() {
        val encoder = Class.forName("net.logstash.logback.encoder.LogstashEncoder")
        assertNotNull(encoder)
    }
}
