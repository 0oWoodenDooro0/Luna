package luna.core

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class LoggingInfrastructureTest {
    @Test
    fun `should have logback classes available`() {
        val loggerContext = Class.forName("ch.qos.logback.classic.LoggerContext")
        assertNotNull(loggerContext)
    }

    @Test
    fun `should have logback configuration with RollingFileAppender and SizeBasedTriggeringPolicy`() {
        val factory = org.slf4j.LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
        val rootLogger = factory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val appender = rootLogger.getAppender("JSON_FILE")
        assertNotNull(appender, "JSON_FILE appender should be configured")
        assertTrue(appender is ch.qos.logback.core.rolling.RollingFileAppender<*>, "Appender should be RollingFileAppender")
        
        val triggeringPolicy = (appender as ch.qos.logback.core.rolling.RollingFileAppender<*>).triggeringPolicy
        assertTrue(triggeringPolicy is ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy<*>, "Triggering policy should be SizeBasedTriggeringPolicy")
        val sizePolicy = triggeringPolicy as ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy<*>
        // Logback 1.5.x uses FileSize class internally for maxFileSize
        assertTrue(sizePolicy.maxFileSize.toString().contains("10 MB"), "Max file size should be 10MB (actual: ${sizePolicy.maxFileSize})")
    }
}
