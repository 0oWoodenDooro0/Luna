package luna.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnvSyncCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var projectRoot: File
    private lateinit var exampleFile: File

    @BeforeEach
    fun setUp() {
        projectRoot = File(".").canonicalFile
        exampleFile = File(projectRoot, ".env.example")
        assertTrue(exampleFile.exists(), ".env.example must exist at project root")
    }

    @AfterEach
    fun tearDown() {
        // TempDir automatically cleans up
    }

    private fun runCliCapture(args: Array<String>): Triple<Boolean, Int?, String> {
        var exitCode: Int? = null
        val byteOut = ByteArrayOutputStream()
        val printOut = PrintStream(byteOut)

        val handled =
            EnvSyncCli.handleCli(
                args = args,
                out = printOut,
                err = printOut,
                exitProcess = { code -> exitCode = code },
            )
        printOut.flush()
        return Triple(handled, exitCode, byteOut.toString())
    }

    @Test
    fun `check env report outputs missing required variables and exits with non-zero code when required variables are missing`() {
        val testEnv = File(tempDir.toFile(), ".env")
        testEnv.writeText("PORT=8080\n")

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--check-env",
                    "--env-file=${testEnv.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(1, exitCode)
        assertTrue(output.contains("❌ Missing Required"))
        assertTrue(output.contains("DISCORD_TOKEN"))
        assertTrue(output.contains("DISCORD_CLIENT_ID"))
        assertTrue(output.contains("DISCORD_CLIENT_SECRET"))
        assertTrue(output.contains("Audit Failed"))
    }

    @Test
    fun `check env report outputs missing optional variables and exits with zero code when all required variables are present`() {
        val testEnv = File(tempDir.toFile(), ".env")
        testEnv.writeText(
            """
            DISCORD_TOKEN=test_bot_token_value
            DISCORD_CLIENT_ID=1234567890
            DISCORD_CLIENT_SECRET=test_client_secret_value
            """.trimIndent(),
        )

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--check-env",
                    "--env-file=${testEnv.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(0, exitCode)
        assertFalse(output.contains("❌ Missing Required"))
        assertTrue(output.contains("⚠️ Missing Optional"))
        assertTrue(output.contains("PORT"))
        assertTrue(output.contains("Audit Passed with warnings"))
    }

    @Test
    fun `check env report outputs clean summary and exits with zero code when all variables match specification`() {
        val testEnv = File(tempDir.toFile(), ".env")
        // Copy entire .env.example with dummy values so every variable is present
        val lines =
            exampleFile.readLines().map { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("# PLUGINS_") && trimmed.contains("=")) {
                    trimmed.removePrefix("#").trim()
                } else if (trimmed.startsWith("# MCP_") && trimmed.contains("=")) {
                    trimmed.removePrefix("#").trim()
                } else {
                    line
                }
            }
        testEnv.writeText(lines.joinToString("\n"))

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--check-env",
                    "--env-file=${testEnv.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(0, exitCode)
        assertFalse(output.contains("❌ Missing Required"))
        assertTrue(output.contains("Audit Passed") || output.contains("All environment variables match"))
    }

    @Test
    fun `check env report detects deprecated or unknown variables`() {
        val testEnv = File(tempDir.toFile(), ".env")
        testEnv.writeText(
            """
            DISCORD_TOKEN=test_bot_token_value
            DISCORD_CLIENT_ID=1234567890
            DISCORD_CLIENT_SECRET=test_client_secret_value
            CUSTOM_DEPRECATED_VAR=some_old_setting
            """.trimIndent(),
        )

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--check-env",
                    "--env-file=${testEnv.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(0, exitCode)
        assertTrue(output.contains("🗑️ Deprecated/Unknown"))
        assertTrue(output.contains("CUSTOM_DEPRECATED_VAR"))
    }

    @Test
    fun `sync env creates timestamped backup file matching env bak pattern before modifying`() {
        val testEnv = File(tempDir.toFile(), ".env")
        val initialContent = "DISCORD_TOKEN=existing_secret_12345\nPORT=9090\n"
        testEnv.writeText(initialContent)

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--sync-env",
                    "--env-file=${testEnv.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(0, exitCode)
        assertTrue(output.contains("📦 Created backup:"))
        assertTrue(output.contains(".env.bak."))

        val backupFiles = tempDir.toFile().listFiles { f -> f.name.startsWith(".env.bak.") }
        assertNotNull(backupFiles)
        assertTrue(backupFiles.isNotEmpty(), "At least one .env.bak.* file must be created")
        assertEquals(initialContent, backupFiles.first().readText())
    }

    @Test
    fun `sync env preserves all existing secrets and configurations intact`() {
        val testEnv = File(tempDir.toFile(), ".env")
        val originalSecret = "DISCORD_TOKEN=super_precious_bot_token"
        val customAppId = "DISCORD_CLIENT_ID=custom_client_9999"
        val customSecret = "DISCORD_CLIENT_SECRET=custom_secret_8888"
        val customPort = "PORT=7777"
        val customUserSetting = "MY_SPECIAL_VARIABLE=persisted_user_value"

        val initialContent =
            listOf(
                "# My Custom Comments",
                originalSecret,
                customAppId,
                customSecret,
                customPort,
                customUserSetting,
            ).joinToString("\n") + "\n"

        testEnv.writeText(initialContent)

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--sync-env",
                    "--env-file=${testEnv.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(0, exitCode)

        val updatedContent = testEnv.readText()
        assertTrue(updatedContent.contains(originalSecret))
        assertTrue(updatedContent.contains(customAppId))
        assertTrue(updatedContent.contains(customSecret))
        assertTrue(updatedContent.contains(customPort))
        assertTrue(updatedContent.contains(customUserSetting))
        assertTrue(updatedContent.contains("# My Custom Comments"))
        assertTrue(output.contains("✅ Environment synchronization complete."))
    }

    @Test
    fun `sync env appends missing keys with documentation comments and default values`() {
        val testEnv = File(tempDir.toFile(), ".env")
        testEnv.writeText("DISCORD_TOKEN=secret_token\nDISCORD_CLIENT_ID=123\nDISCORD_CLIENT_SECRET=secret456\n")

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--sync-env",
                    "--env-file=${testEnv.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(0, exitCode)
        assertTrue(output.contains("➕ Appended"))

        val content = testEnv.readText()
        // Should append PORT, BASE_URL, GEMINI_MODEL etc with documentation
        assertTrue(content.contains("PORT=8080"))
        assertTrue(content.contains("BASE_URL=http://localhost:8080/s/"))
        assertTrue(content.contains("GEMINI_MODEL=gemini-3.5-flash-lite"))
        assertTrue(content.contains("# [選填]"))
    }

    @Test
    fun `sync env is idempotent and does not create backup or modify file when already in sync`() {
        val testEnv = File(tempDir.toFile(), ".env")
        testEnv.writeText("DISCORD_TOKEN=secret_token\nDISCORD_CLIENT_ID=123\nDISCORD_CLIENT_SECRET=secret456\n")

        // First sync
        runCliCapture(
            arrayOf(
                "--sync-env",
                "--env-file=${testEnv.absolutePath}",
                "--example-file=${exampleFile.absolutePath}",
            ),
        )

        val contentAfterFirstSync = testEnv.readText()
        val backupsAfterFirstSync = tempDir.toFile().listFiles { f -> f.name.startsWith(".env.bak.") }?.size ?: 0

        // Second sync
        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--sync-env",
                    "--env-file=${testEnv.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(0, exitCode)
        assertTrue(output.contains("already in sync"))

        val backupsAfterSecondSync = tempDir.toFile().listFiles { f -> f.name.startsWith(".env.bak.") }?.size ?: 0
        assertEquals(backupsAfterFirstSync, backupsAfterSecondSync, "No additional backup should be created on redundant sync")
        assertEquals(contentAfterFirstSync, testEnv.readText(), "File content must not change when already in sync")
    }

    @Test
    fun `check env exits with non-zero code and reports error when target file does not exist`() {
        val nonExistent = File(tempDir.toFile(), "non-existent.env")

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--check-env",
                    "--env-file=${nonExistent.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(1, exitCode)
        assertTrue(output.contains("does not exist") || output.contains("❌"))
    }

    @Test
    fun `sync env exits with non-zero code and reports error when target file does not exist`() {
        val nonExistent = File(tempDir.toFile(), "non-existent.env")

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--sync-env",
                    "--env-file=${nonExistent.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(1, exitCode)
        assertTrue(output.contains("does not exist") || output.contains("❌"))
    }

    @Test
    fun `check env handles empty env file and reports all required variables as missing`() {
        val emptyEnv = File(tempDir.toFile(), ".env")
        emptyEnv.writeText("")

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--check-env",
                    "--env-file=${emptyEnv.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(1, exitCode)
        assertTrue(output.contains("❌ Missing Required"))
        assertTrue(output.contains("DISCORD_TOKEN"))
    }

    @Test
    fun `sync env handles empty env file and creates backup and appends all example variables`() {
        val emptyEnv = File(tempDir.toFile(), ".env")
        emptyEnv.writeText("")

        val (handled, exitCode, output) =
            runCliCapture(
                arrayOf(
                    "--sync-env",
                    "--env-file=${emptyEnv.absolutePath}",
                    "--example-file=${exampleFile.absolutePath}",
                ),
            )

        assertTrue(handled)
        assertEquals(0, exitCode)
        assertTrue(output.contains("📦 Created backup:"))
        assertTrue(output.contains("➕ Appended"))

        val content = emptyEnv.readText()
        assertTrue(content.contains("DISCORD_TOKEN"))
        assertTrue(content.contains("PORT=8080"))
    }

    @Test
    fun `sync env exits with non-zero code and handles non-writable target file`() {
        val readOnlyEnv = File(tempDir.toFile(), ".readonly.env")
        readOnlyEnv.writeText("DISCORD_TOKEN=secret\n")
        val setReadOnlySucceeded = readOnlyEnv.setReadOnly()

        if (setReadOnlySucceeded && !readOnlyEnv.canWrite()) {
            val (handled, exitCode, output) =
                runCliCapture(
                    arrayOf(
                        "--sync-env",
                        "--env-file=${readOnlyEnv.absolutePath}",
                        "--example-file=${exampleFile.absolutePath}",
                    ),
                )

            assertTrue(handled)
            assertEquals(1, exitCode)
            assertTrue(output.contains("❌") || output.contains("Permission denied") || output.contains("read-only"))
            // Reset write permission so TempDir cleanup succeeds
            readOnlyEnv.setWritable(true)
        }
    }

    @Test
    fun `handleCli returns false when neither check env nor sync env flags are passed`() {
        val (handled, exitCode, _) = runCliCapture(arrayOf("--foo", "--bar"))
        assertFalse(handled)
        assertEquals(null, exitCode)
    }

    @Test
    fun `handleCli in check mode audits container environment variables when default dot-env file is absent`() {
        val outBytes = java.io.ByteArrayOutputStream()
        val errBytes = java.io.ByteArrayOutputStream()
        val outStream = java.io.PrintStream(outBytes)
        val errStream = java.io.PrintStream(errBytes)
        var capturedExit: Int? = null

        // Using a non-existent default environment file path to trigger container environment fallback
        val emptyDir = File(tempDir.toFile(), "empty-dir")
        emptyDir.mkdirs()
        val nonExistentDefaultFile = File(emptyDir, ".env")

        val handled =
            EnvSyncCli.handleCli(
                args = arrayOf("--check-env", "--example-file=${exampleFile.absolutePath}", "--file=${nonExistentDefaultFile.absolutePath}"),
                out = outStream,
                err = errStream,
                exitProcess = { capturedExit = it },
            )

        assertTrue(handled)
        // Explicit file flag should fail with file not found
        assertEquals(1, capturedExit)
        assertTrue(errBytes.toString().contains("does not exist"))

        // Implicit default without explicit flag should audit System.getenv()
        val outBytesImplicit = java.io.ByteArrayOutputStream()
        val errBytesImplicit = java.io.ByteArrayOutputStream()
        var capturedExitImplicit: Int? = null

        val handledImplicit =
            EnvSyncCli.handleCli(
                args = arrayOf("--check-env", "--example-file=${exampleFile.absolutePath}"),
                out = java.io.PrintStream(outBytesImplicit),
                err = java.io.PrintStream(errBytesImplicit),
                exitProcess = { capturedExitImplicit = it },
            )

        assertTrue(handledImplicit)
        val outputImplicit = outBytesImplicit.toString()
        assertTrue(
            outputImplicit.contains("Environment Configuration Audit"),
            "Implicit check when .env is absent must run audit against container environment",
        )
    }
}
