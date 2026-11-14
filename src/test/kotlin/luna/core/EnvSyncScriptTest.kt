package luna.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnvSyncScriptTest {
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

    @Test
    fun `standalone shell script in check mode exits with non-zero code on missing required variables`() {
        val script = File(projectRoot, "scripts/sync-env.sh")
        assertTrue(script.exists(), "scripts/sync-env.sh must exist")
        assertTrue(script.canExecute(), "scripts/sync-env.sh must be executable")

        val testEnv = File(tempDir.toFile(), ".sh_test.env")
        testEnv.writeText("PORT=8080\n")

        val process =
            ProcessBuilder(
                script.absolutePath,
                "--check",
                "-f",
                testEnv.absolutePath,
                "-e",
                exampleFile.absolutePath,
                "--no-color",
            ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(1, exitCode)
        assertTrue(output.contains("❌ Missing Required"))
        assertTrue(output.contains("DISCORD_TOKEN"))
    }

    @Test
    fun `standalone shell script in check mode exits with zero code when required variables are satisfied`() {
        val script = File(projectRoot, "scripts/sync-env.sh")
        val testEnv = File(tempDir.toFile(), ".sh_test_valid.env")
        testEnv.writeText(
            """
            DISCORD_TOKEN=token123
            DISCORD_CLIENT_ID=client123
            DISCORD_CLIENT_SECRET=secret123
            """.trimIndent(),
        )

        val process =
            ProcessBuilder(
                script.absolutePath,
                "--check",
                "-f",
                testEnv.absolutePath,
                "-e",
                exampleFile.absolutePath,
                "--no-color",
            ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(0, exitCode)
        assertTrue(output.contains("Audit Passed with warnings"))
        assertTrue(output.contains("⚠️ Missing Optional"))
    }

    @Test
    fun `standalone shell script in sync mode creates backup file and appends missing variables`() {
        val script = File(projectRoot, "scripts/sync-env.sh")
        val testEnv = File(tempDir.toFile(), ".sh_test_sync.env")
        val initialContent = "DISCORD_TOKEN=sh_token_value\nDISCORD_CLIENT_ID=sh_client\n"
        testEnv.writeText(initialContent)

        val process =
            ProcessBuilder(
                script.absolutePath,
                "--sync",
                "-f",
                testEnv.absolutePath,
                "-e",
                exampleFile.absolutePath,
                "--no-color",
            ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(0, exitCode)
        assertTrue(output.contains("📦 Created backup:"))
        assertTrue(output.contains("➕ Appended"))

        val content = testEnv.readText()
        assertTrue(content.contains(initialContent.trim()))
        assertTrue(content.contains("DISCORD_CLIENT_SECRET"))
        assertTrue(content.contains("PORT=8080"))

        val backups = tempDir.toFile().listFiles { f -> f.name.startsWith(".sh_test_sync.env.bak.") }
        assertNotNull(backups)
        assertTrue(backups.isNotEmpty(), "Shell script must create backup file")
        assertEquals(initialContent, backups.first().readText())
    }

    @Test
    fun `standalone shell script in check mode exits with non-zero code when target file is missing`() {
        val script = File(projectRoot, "scripts/sync-env.sh")
        val nonExistent = File(tempDir.toFile(), ".does_not_exist.env")

        val process =
            ProcessBuilder(
                script.absolutePath,
                "--check",
                "-f",
                nonExistent.absolutePath,
                "-e",
                exampleFile.absolutePath,
                "--no-color",
            ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(1, exitCode)
        assertTrue(output.contains("does not exist") || output.contains("❌"))
    }

    @Test
    fun `standalone shell script in sync mode is idempotent when target file is already in sync`() {
        val script = File(projectRoot, "scripts/sync-env.sh")
        val testEnv = File(tempDir.toFile(), ".sh_test_idempotent.env")
        testEnv.writeText("DISCORD_TOKEN=tok\n")

        // First sync
        ProcessBuilder(
            script.absolutePath,
            "--sync",
            "-f",
            testEnv.absolutePath,
            "-e",
            exampleFile.absolutePath,
            "--no-color",
        ).start().waitFor()

        val backupsCount1 = tempDir.toFile().listFiles { f -> f.name.contains(".sh_test_idempotent.env.bak.") }?.size ?: 0

        // Second sync
        val process2 =
            ProcessBuilder(
                script.absolutePath,
                "--sync",
                "-f",
                testEnv.absolutePath,
                "-e",
                exampleFile.absolutePath,
                "--no-color",
            ).redirectErrorStream(true).start()

        val output2 = process2.inputStream.bufferedReader().readText()
        val exitCode2 = process2.waitFor()

        assertEquals(0, exitCode2)
        assertTrue(output2.contains("already in sync"))

        val backupsCount2 = tempDir.toFile().listFiles { f -> f.name.contains(".sh_test_idempotent.env.bak.") }?.size ?: 0
        assertEquals(backupsCount1, backupsCount2, "Redundant sync must not create extra backup files")
    }

    @Test
    fun `standalone shell script in check mode satisfies required variables when legacy aliases like DISCORD_APP_ID are set`() {
        val script = File(projectRoot, "scripts/sync-env.sh")
        val testEnv = File(tempDir.toFile(), ".sh_test_alias.env")
        testEnv.writeText(
            """
            DISCORD_TOKEN=test-bot-token
            DISCORD_APP_ID=test-application-id
            DISCORD_CLIENT_SECRET=test-secret
            """.trimIndent(),
        )

        val process =
            ProcessBuilder(
                script.absolutePath,
                "--check",
                "-f",
                testEnv.absolutePath,
                "-e",
                exampleFile.absolutePath,
                "--no-color",
            ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(0, exitCode, "Configuration with DISCORD_APP_ID alias must pass check mode with exit 0. Output:\n$output")
        assertFalse(output.contains("Missing Required"), "Must not report DISCORD_CLIENT_ID missing when DISCORD_APP_ID is present")
    }
}
