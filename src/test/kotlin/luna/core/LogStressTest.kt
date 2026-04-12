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
        // To hit 1MB, we need about 3,000 entries.
        // We do 5,000 to ensure we trigger the 1MB limit.
        repeat(5000) { i ->
            JsonLogger.log(
                layer = "STRESS",
                component = "LogStressTest",
                operation = "stressTest",
                data = mapOf("index" to i, "payload" to "x".repeat(100))
            )
        }

        val logFiles = logDir.listFiles() ?: emptyArray()
        assertTrue(logFiles.isNotEmpty(), "Log files should exist")

        // In SizeAndTimeBasedRollingPolicy, the active file is luna-json.log
        // Backups will have the date and index
        
        println("Found log files: ${logFiles.joinToString { it.name + " (" + it.length() / 1024 + " KB)" }}")
        
        // With 5k entries (~2.5MB) and 1MB limit, we expect rotation
        assertTrue(logFiles.size >= 2, "Should have rotated (found ${logFiles.size} files)")
        logFiles.forEach {
            assertTrue(it.length() <= 1.1 * 1024 * 1024, "File ${it.name} exceeds 1MB limit")
        }
    }
}
