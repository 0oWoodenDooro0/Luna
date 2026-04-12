package luna.core

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoggingInfrastructureTest {
    @Test
    fun `should have logback classes available`() {
        val loggerContext = Class.forName("ch.qos.logback.classic.LoggerContext")
        assertNotNull(loggerContext)
    }

    @Test
    fun `should have logback configuration with RollingFileAppender`() {
        val factory = org.slf4j.LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
        val rootLogger = factory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val appender = rootLogger.getAppender("JSON_FILE")
        assertNotNull(appender, "JSON_FILE appender should be configured")
        assertTrue(appender is ch.qos.logback.core.rolling.RollingFileAppender<*>, "Appender should be RollingFileAppender")
    }
}
