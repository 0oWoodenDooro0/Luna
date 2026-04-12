package luna.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class LogStressTest {
    @Test
    fun `should rotate logs and maintain size limit`() {
        val logDir = File("data/logs")
        if (!logDir.exists()) logDir.mkdirs()
        
        // Clean up old logs to start fresh
        logDir.listFiles()?.forEach { it.delete() }

        // Each entry is roughly 300-400 bytes. 
        // To hit 10MB, we need about 30,000 entries.
        // We do 50,000 to ensure we trigger the 10MB limit.
        repeat(50000) { i ->
            JsonLogger.log(
                layer = "STRESS",
                component = "LogStressTest",
                operation = "stressTest",
                data = mapOf("index" to i, "payload" to "x".repeat(100))
            )
        }

        val logFiles = logDir.listFiles() ?: emptyArray()
        assertTrue(logFiles.isNotEmpty(), "Log files should exist")

        // In FixedWindowRollingPolicy with maxIndex 1, we expect:
        // 1. luna-json.log (active)
        // 2. luna-json.1.log.zip (one backup)
        
        println("Found log files: ${logFiles.joinToString { it.name + " (" + it.length() / 1024 + " KB)" }}")
        
        assertTrue(logFiles.size <= 2, "Should have at most 2 files (active + 1 zip backup)")
        logFiles.forEach {
            assertTrue(it.length() <= 11 * 1024 * 1024, "File ${it.name} exceeds 10MB limit")
        }
    }
}
