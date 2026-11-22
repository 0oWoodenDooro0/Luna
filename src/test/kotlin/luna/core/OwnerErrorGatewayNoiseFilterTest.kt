package luna.core

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.EOFException
import java.net.SocketException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerErrorGatewayNoiseFilterTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-19T21:08:58Z"), ZoneOffset.UTC)

    private fun webSocketReaderStackTrace(): String =
        """
        java.io.EOFException
        	at okio.RealBufferedSource.require(RealBufferedSource.kt:225)
        	at okhttp3.internal.ws.WebSocketReader.readHeader(WebSocketReader.kt:125)
        	at okhttp3.internal.ws.WebSocketReader.processNextFrame(WebSocketReader.kt:108)
        	at okhttp3.internal.ws.RealWebSocket.loopReader(RealWebSocket.kt:317)
        """.trimIndent()

    @Test
    fun `suppresses transient DefaultGateway OkHttp WebSocket EOFException and SocketException while preserving real errors`() =
        runTest {
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    clock = fixedClock,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()

            val eofDisconnect =
                OwnerErrorEvent(
                    timestamp = fixedClock.instant(),
                    severity = ErrorAlertSeverity.ERROR,
                    loggerName = "dev.kord.gateway.DefaultGateway",
                    message = "java.io.EOFException",
                    throwableClass = "java.io.EOFException",
                    stackTrace = webSocketReaderStackTrace(),
                )
            val resetDisconnect =
                OwnerErrorEvent(
                    timestamp = fixedClock.instant(),
                    severity = ErrorAlertSeverity.ERROR,
                    loggerName = "dev.kord.gateway.DefaultGateway",
                    message = "Connection reset",
                    throwableClass = "java.net.SocketException",
                    stackTrace =
                        """
                        java.net.SocketException: Connection reset
                        	at java.base/sun.nio.ch.NioSocketImpl.read(Unknown Source)
                        	at okhttp3.internal.ws.WebSocketReader.readHeader(WebSocketReader.kt:125)
                        	at okhttp3.internal.ws.RealWebSocket.loopReader(RealWebSocket.kt:317)
                        """.trimIndent(),
                )
            val fatalGatewayError =
                OwnerErrorEvent(
                    timestamp = fixedClock.instant(),
                    severity = ErrorAlertSeverity.ERROR,
                    loggerName = "dev.kord.gateway.DefaultGateway",
                    message = "Gateway handshake failed: 4004 Authentication failed",
                    throwableClass = "java.lang.IllegalStateException",
                    stackTrace =
                        """
                        java.lang.IllegalStateException: Gateway handshake failed
                        	at dev.kord.gateway.DefaultGateway.start(DefaultGateway.kt:120)
                        """.trimIndent(),
                )
            val nonWsEof =
                OwnerErrorEvent(
                    timestamp = fixedClock.instant(),
                    severity = ErrorAlertSeverity.ERROR,
                    loggerName = "dev.kord.gateway.DefaultGateway",
                    message = "Unexpected EOF decoding payload",
                    throwableClass = "java.io.EOFException",
                    stackTrace =
                        """
                        java.io.EOFException: Unexpected EOF decoding payload
                        	at dev.kord.gateway.DefaultGateway.handleFrame(DefaultGateway.kt:210)
                        """.trimIndent(),
                )
            val restSocketError =
                OwnerErrorEvent(
                    timestamp = fixedClock.instant(),
                    severity = ErrorAlertSeverity.ERROR,
                    loggerName = "dev.kord.rest.DefaultRequestHandler",
                    message = "Connection reset",
                    throwableClass = "java.net.SocketException",
                    stackTrace = resetDisconnect.stackTrace,
                )

            try {
                notifier.submit(eofDisconnect)
                notifier.submit(resetDisconnect)
                notifier.submit(fatalGatewayError)
                notifier.submit(nonWsEof)
                notifier.submit(restSocketError)
            } finally {
                notifier.flushAndClose()
            }

            assertEquals(3, delivered.size, "Only the 3 non-transient errors should be delivered")
            val messages = delivered.map { it.second }
            assertFalse(
                messages.any { it.contains("WebSocketReader") && it.contains("DefaultGateway") },
                "Transient DefaultGateway WebSocketReader errors must be suppressed",
            )
            assertTrue(
                messages.any { it.contains("Gateway handshake failed: 4004 Authentication failed") },
                "Non-transient DefaultGateway error must still notify",
            )
            assertTrue(
                messages.any { it.contains("Unexpected EOF decoding payload") },
                "EOFException outside WebSocketReader must still notify",
            )
            assertTrue(
                messages.any { it.contains("DefaultRequestHandler") && it.contains("Connection reset") },
                "SocketException on non-DefaultGateway logger must still notify",
            )
        }

    @Test
    fun `OwnerErrorNotifier end-to-end drops DefaultGateway WebSocket EOF and Connection reset logs via Logback`() =
        runTest {
            val delivered = CopyOnWriteArrayList<Pair<Snowflake, String>>()
            val notifier =
                OwnerErrorNotifier(
                    config = ErrorNotifyConfig(recipientUserIds = listOf(Snowflake(42uL))),
                    clock = fixedClock,
                    dmSender = { userId, content -> delivered.add(userId to content) },
                )
            notifier.onReady()

            val context = LoggerFactory.getILoggerFactory() as LoggerContext
            val gatewayLogger = LoggerFactory.getLogger("dev.kord.gateway.DefaultGateway") as Logger
            val appender =
                OwnerErrorLogbackAppender(notifier).apply {
                    this.context = context
                    this.name = "TEST_GATEWAY_FILTER_${System.nanoTime()}"
                    start()
                }
            gatewayLogger.addAppender(appender)

            val wsFrames =
                arrayOf(
                    StackTraceElement("okio.RealBufferedSource", "require", "RealBufferedSource.kt", 225),
                    StackTraceElement("okhttp3.internal.ws.WebSocketReader", "readHeader", "WebSocketReader.kt", 125),
                    StackTraceElement("okhttp3.internal.ws.RealWebSocket", "loopReader", "RealWebSocket.kt", 317),
                )
            val eofThrowable = EOFException().apply { stackTrace = wsFrames }
            val resetThrowable = SocketException("Connection reset").apply { stackTrace = wsFrames }

            try {
                gatewayLogger.error("", eofThrowable)
                gatewayLogger.error("", resetThrowable)
                gatewayLogger.error("Fatal gateway state corruption", IllegalStateException("Corrupt session"))
            } finally {
                gatewayLogger.detachAppender(appender)
                appender.stop()
                notifier.flushAndClose()
            }

            assertEquals(1, delivered.size, "Only non-transient gateway error should deliver a DM")
            assertTrue(
                delivered.single().second.contains("Fatal gateway state corruption"),
                "Delivered DM must contain the non-transient gateway error message",
            )
        }
}
