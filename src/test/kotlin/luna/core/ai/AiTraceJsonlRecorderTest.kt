package luna.core.ai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiTraceJsonlRecorderTest {
    private class MutableClock(
        private var currentInstant: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant(): Instant = currentInstant

        fun setInstant(instant: Instant) {
            currentInstant = instant
        }
    }

    @Test
    fun `JsonlAiTraceRecorder rotates files daily and prunes by age and total byte size`(
        @TempDir tempDir: Path,
    ) = runTest {
        val logDir = tempDir.resolve("traces")
        Files.createDirectories(logDir)

        // Pre-populate files: 20 days old (age pruned), 5 days old (kept initially, then size pruned), unrelated file (preserved)
        val oldFile = logDir.resolve("ai-2026-10-20.jsonl")
        val midFile = logDir.resolve("ai-2026-11-09.jsonl")
        val recentFile = logDir.resolve("ai-2026-11-13.jsonl")
        val unrelatedFile = logDir.resolve("notes.txt")

        Files.writeString(oldFile, "x".repeat(150) + "\n")
        Files.writeString(midFile, "y".repeat(500) + "\n")
        Files.writeString(recentFile, "z".repeat(400) + "\n")
        Files.writeString(unrelatedFile, "keep me")

        val clock = MutableClock(Instant.parse("2026-11-14T12:00:00Z"))
        // maxDays = 14 -> 2026-10-20 deleted on startup.
        // maxTotalBytes = 1100 -> midFile (501B) + recentFile (401B) = 902B <= 1100B on startup;
        // writing Day 1 (~420B) + Day 2 (~420B) prunes midFile and recentFile while keeping Day 1 + Day 2 <= 1100B.
        val recorder =
            JsonlAiTraceRecorder(
                traceDir = logDir,
                maxDays = 14,
                maxTotalBytes = 1100L,
                clock = clock,
                emitCorrelationLog = false,
            )

        val session1 =
            recorder.startSession(
                pipeline = AiTracePipeline.CHATTER,
                trigger = AiTraceTrigger.AMBIENT,
                discord = AiTraceDiscordContext(guildId = "g", channelId = "c", messageId = "m1", authorId = "a"),
            )
        session1.skip("Decision probability below threshold")
        session1.finish()

        // Wait for day 1 trace to be persisted before advancing clock to day 2
        val day1File = logDir.resolve("ai-2026-11-14.jsonl")
        val deadline = System.currentTimeMillis() + 5000L
        while (!Files.exists(day1File) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(Files.exists(day1File), "Day 1 trace should be written before rollover")

        // Advance clock to next UTC day for daily rotation
        clock.setInstant(Instant.parse("2026-11-15T01:00:00Z"))
        val session2 =
            recorder.startSession(
                pipeline = AiTracePipeline.CHATTER,
                trigger = AiTraceTrigger.MENTION,
                discord = AiTraceDiscordContext(guildId = "g", channelId = "c", messageId = "m2", authorId = "a", isDirectMention = true),
            )
        session2.complete("Reply on day 2")
        session2.finish()

        recorder.flushAndClose()

        assertFalse(Files.exists(oldFile), "File older than maxDays must be deleted")
        assertFalse(Files.exists(midFile), "Oldest remaining file must be pruned when total size exceeds maxTotalBytes")
        assertFalse(Files.exists(recentFile), "Second oldest file must be pruned when total size exceeds maxTotalBytes")
        assertTrue(Files.exists(logDir.resolve("ai-2026-11-14.jsonl")), "Day 1 file must exist")
        assertTrue(Files.exists(logDir.resolve("ai-2026-11-15.jsonl")), "Day 2 rotated file must exist")
        assertTrue(Files.exists(unrelatedFile), "Non-trace files in directory must not be touched")
    }

    @Test
    fun `JsonlAiTraceRecorder fails open when bounded queue of 256 overflows`(
        @TempDir tempDir: Path,
    ) = runTest {
        val logDir = tempDir.resolve("traces")
        val writerEnteredLatch = CountDownLatch(1)
        val releaseWriterLatch = CountDownLatch(1)

        val recorder =
            JsonlAiTraceRecorder(
                traceDir = logDir,
                queueCapacity = 256,
                emitCorrelationLog = false,
                beforeWriteHook = {
                    writerEnteredLatch.countDown()
                    releaseWriterLatch.await(5, TimeUnit.SECONDS)
                },
            )

        val jsonLogger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        jsonLogger.addAppender(listAppender)

        try {
            // Send first trace to pin the writer thread on beforeWriteHook
            val firstSession =
                recorder.startSession(
                    pipeline = AiTracePipeline.SUMMARY,
                    trigger = AiTraceTrigger.REACTION,
                    discord = AiTraceDiscordContext(guildId = "g", channelId = "c", messageId = "m0", authorId = "a"),
                )
            firstSession.complete("first")
            firstSession.finish()
            assertTrue(writerEnteredLatch.await(2, TimeUnit.SECONDS), "Writer should pick up first item")

            // Now fill all 256 slots + overflow without throwing or blocking
            for (i in 1..280) {
                val s =
                    recorder.startSession(
                        pipeline = AiTracePipeline.SUMMARY,
                        trigger = AiTraceTrigger.REACTION,
                        discord = AiTraceDiscordContext(guildId = "g", channelId = "c", messageId = "m$i", authorId = "a"),
                    )
                s.complete("msg-$i")
                s.finish()
            }

            releaseWriterLatch.countDown()
            recorder.flushAndClose()

            val warnLogs = listAppender.list.filter { it.level == Level.WARN }
            assertTrue(warnLogs.isNotEmpty(), "Queue overflow must emit a WARN log")
            val writtenCount =
                Files
                    .list(logDir)
                    .use { stream ->
                        stream.filter { it.fileName.toString().endsWith(".jsonl") }.toList()
                    }.sumOf { Files.readAllLines(it).size }
            assertTrue(writtenCount in 256..257, "Expected 256..257 persisted items after overflow drop, got $writtenCount")
        } finally {
            releaseWriterLatch.countDown()
            jsonLogger.detachAppender(listAppender)
        }
    }

    @Test
    fun `JsonlAiTraceRecorder fails open and logs warning when target path is unwritable`(
        @TempDir tempDir: Path,
    ) = runTest {
        // Create a regular file where the directory should be so createDirectories fails cleanly on all OS/root perms
        val fileAsDir = tempDir.resolve("blocked-trace-dir")
        Files.writeString(fileAsDir, "not a directory")

        val recorder =
            JsonlAiTraceRecorder(
                traceDir = fileAsDir,
                emitCorrelationLog = false,
            )

        val jsonLogger = LoggerFactory.getLogger("JSON_LOGGER") as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        jsonLogger.addAppender(listAppender)

        try {
            val session =
                recorder.startSession(
                    pipeline = AiTracePipeline.CHATTER,
                    trigger = AiTraceTrigger.MENTION,
                    discord = AiTraceDiscordContext(guildId = "g", channelId = "c", messageId = "m1", authorId = "a", isDirectMention = true),
                )
            session.fail(AiTraceFailureCategory.UNEXPECTED_ERROR, "boom")
            session.finish()

            // Must not throw
            recorder.flushAndClose()

            val warnLogs = listAppender.list.filter { it.level == Level.WARN }
            assertTrue(warnLogs.isNotEmpty(), "Unwritable trace directory must log a WARN and fail open")
        } finally {
            jsonLogger.detachAppender(listAppender)
        }
    }

    @Test
    fun `AiTraceSession dispatches registered extraSecrets via AiTraceRecorder record overload`() {
        var recordedTrace: AiTraceRecord? = null
        var recordedSecrets: Set<String>? = null
        val customRecorder =
            object : AiTraceRecorder {
                override fun isEnabled(): Boolean = true

                override fun startSession(
                    pipeline: AiTracePipeline,
                    trigger: AiTraceTrigger,
                    discord: AiTraceDiscordContext,
                ): AiTraceSession =
                    AiTraceSession(
                        recorder = this,
                        pipeline = pipeline,
                        trigger = trigger,
                        discord = discord,
                        isEnabled = true,
                        emitCorrelationLog = false,
                    )

                override fun record(trace: AiTraceRecord) {
                    recordedTrace = trace
                }

                override fun record(
                    trace: AiTraceRecord,
                    extraSecrets: Set<String>,
                ) {
                    recordedTrace = trace
                    recordedSecrets = extraSecrets
                }

                override suspend fun flushAndClose(timeoutMs: Long) = Unit
            }

        val session =
            customRecorder.startSession(
                pipeline = AiTracePipeline.CHATTER,
                trigger = AiTraceTrigger.MENTION,
                discord = AiTraceDiscordContext(channelId = "c1", messageId = "m1", isDirectMention = true),
            )
        session.registerSecret("dynamic-secret-123")
        session.complete("done")
        session.finish()

        assertNotNull(recordedTrace)
        assertEquals(setOf("dynamic-secret-123"), recordedSecrets)
    }
}
