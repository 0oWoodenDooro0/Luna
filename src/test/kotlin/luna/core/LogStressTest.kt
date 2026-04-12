package luna.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

class LogStressTest {
    @Test
    fun `should rotate logs and maintain size limit`() {
        val logDir = File("data/logs")
        if (!logDir.exists()) logDir.mkdirs()

        // Log a large number of entries to trigger rotation
        // Each entry is roughly 300-400 bytes. 
        // To hit 10MB, we need about 30,000 entries.
        repeat(40000) { i ->
            JsonLogger.log(
                layer = "STRESS",
                component = "LogStressTest",
                operation = "stressTest",
                data = mapOf("index" to i, "payload" to "x".repeat(100))
            )
        }

        val logFiles = logDir.listFiles { _, name -> name.startsWith("luna-json") } ?: emptyArray()
        assertTrue(logFiles.isNotEmpty(), "Log files should exist")

        val totalSize = logFiles.sumOf { it.length() }
        val tenMbInBytes = 11 * 1024 * 1024 // Give some buffer for the current active file
        
        println("Total log size: ${totalSize / 1024 / 1024} MB (${logFiles.size} files)")
        
        // Note: totalSizeCap in Logback is an asynchronous best-effort limit.
        // It might exceed slightly before the cleaner runs.
        assertTrue(totalSize < tenMbInBytes, "Total log size should be around 10MB (actual: $totalSize bytes)")
    }
}
