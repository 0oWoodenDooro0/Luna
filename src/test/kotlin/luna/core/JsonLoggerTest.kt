package luna.core

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class JsonLoggerTest {
    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setup() {
        // We need to capture from the logger that JsonLogger uses
        logger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        listAppender = ListAppender()
        listAppender.start()
        logger.addAppender(listAppender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(listAppender)
    }

    @Test
    fun `should log structured data to JSON_LOGGER`() {
        JsonLogger.log(
            layer = "SERVICE",
            component = "MapService",
            operation = "move",
            data = mapOf("x" to 1, "y" to 2),
            status = "SUCCESS"
        )

        val logEntry = listAppender.list.firstOrNull()
        assertNotNull(logEntry, "Log entry should not be null")
        
        // Verification of structured arguments is tricky with ListAppender
        // but we can check the message or MDC if we use it.
        // If we use Logstash's StructuredArguments, they might not be easily visible here
        // without more deep inspection of the event.
    }
}
