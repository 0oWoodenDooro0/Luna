package luna.core

import java.io.File
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Data model representing a documented variable block parsed from .env.example.
 */
data class EnvExampleBlock(
    val key: String,
    val isRequired: Boolean,
    val defaultValue: String? = null,
    val documentation: String = "",
    val rawBlockText: String = "",
)

/**
 * Report containing the result of auditing a .env file against .env.example.
 */
data class EnvAuditReport(
    val targetFile: File,
    val missingRequired: List<String>,
    val missingOptional: List<Pair<String, String?>>,
    val unknownKeys: List<String>,
) {
    val isValid: Boolean get() = missingRequired.isEmpty()
    val hasDrift: Boolean get() = missingRequired.isNotEmpty() || missingOptional.isNotEmpty() || unknownKeys.isNotEmpty()

    fun formatVisualReport(): String =
        buildString {
            appendLine("🔍 Environment Configuration Audit: ${targetFile.path}")
            appendLine("=".repeat(68))

            if (missingRequired.isNotEmpty()) {
                appendLine("❌ Missing Required (${missingRequired.size}):")
                for (key in missingRequired) {
                    appendLine("   - $key")
                }
                appendLine()
            }

            if (missingOptional.isNotEmpty()) {
                appendLine("⚠️ Missing Optional (${missingOptional.size}):")
                for ((key, defaultVal) in missingOptional) {
                    val defaultSuffix = if (!defaultVal.isNullOrBlank()) " (default: $defaultVal)" else ""
                    appendLine("   - $key$defaultSuffix")
                }
                appendLine()
            }

            if (unknownKeys.isNotEmpty()) {
                appendLine("🗑️ Deprecated/Unknown (${unknownKeys.size}):")
                for (key in unknownKeys) {
                    appendLine("   - $key")
                }
                appendLine()
            }

            appendLine("=".repeat(68))
            if (missingRequired.isNotEmpty()) {
                append("Audit Failed: ${missingRequired.size} required environment variable(s) missing.")
            } else if (missingOptional.isNotEmpty() || unknownKeys.isNotEmpty()) {
                append("Audit Passed with warnings: All required environment variables are set.")
            } else {
                append("✅ All environment variables match .env.example specifications!")
            }
        }
}

/**
 * Result of syncing missing keys into a target .env file.
 */
data class EnvSyncResult(
    val targetFile: File,
    val backupFile: File?,
    val appendedKeys: List<String>,
    val alreadyInSync: Boolean,
) {
    fun formatSummary(): String =
        buildString {
            appendLine("🔄 Synchronizing environment configuration: ${targetFile.path}")
            if (alreadyInSync) {
                appendLine("✅ Environment file is already in sync with .env.example. No changes made.")
            } else {
                if (backupFile != null) {
                    appendLine("📦 Created backup: ${backupFile.path}")
                }
                appendLine("➕ Appended ${appendedKeys.size} missing variable(s):")
                for (key in appendedKeys) {
                    appendLine("   - $key")
                }
                appendLine("✅ Environment synchronization complete.")
            }
        }.trimEnd()
}

/**
 * Luna CLI and tooling for environment variable audit diffing and safe synchronization.
 */
object EnvSyncCli {
    private val VAR_ASSIGN_REGEX = Regex("""^(?:#\s*)?([A-Z0-9_]{3,})\s*=\s*(.*)$""")

    fun resolveEnvFile(args: Array<String>): File {
        for (i in args.indices) {
            val arg = args[i]
            when {
                arg.startsWith("--env-file=") -> return File(arg.substringAfter("--env-file="))
                arg.startsWith("--env=") -> return File(arg.substringAfter("--env="))
                arg.startsWith("--file=") -> return File(arg.substringAfter("--file="))
                (arg == "--env-file" || arg == "--env" || arg == "-f") && i + 1 < args.size -> return File(args[i + 1])
            }
        }
        val fromEnv = System.getenv()["ENV_FILE"] ?: System.getenv()["LUNA_ENV_FILE"]
        if (!fromEnv.isNullOrBlank()) {
            return File(fromEnv)
        }
        return File(".env")
    }

    fun resolveExampleFile(args: Array<String>): File? {
        for (i in args.indices) {
            val arg = args[i]
            when {
                arg.startsWith("--example=") -> return File(arg.substringAfter("--example="))
                arg.startsWith("--example-file=") -> return File(arg.substringAfter("--example-file="))
                (arg == "--example" || arg == "-e") && i + 1 < args.size -> return File(args[i + 1])
            }
        }
        val fromEnv = System.getenv()["ENV_EXAMPLE"] ?: System.getenv()["LUNA_ENV_EXAMPLE"]
        if (!fromEnv.isNullOrBlank()) {
            return File(fromEnv)
        }
        val defaultExample = File(".env.example")
        return if (defaultExample.exists()) defaultExample else null
    }

    fun parseEnvExampleBlocks(exampleContent: String): List<EnvExampleBlock> {
        if (exampleContent.isBlank()) return emptyList()

        val blocks = mutableListOf<EnvExampleBlock>()
        val pendingComments = mutableListOf<String>()

        for (line in exampleContent.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("# ===") ||
                (trimmed.startsWith("# Section") && !trimmed.contains("="))
            ) {
                pendingComments.clear()
                continue
            }

            val assignMatch = VAR_ASSIGN_REGEX.find(trimmed)
            if (assignMatch != null) {
                val key = assignMatch.groupValues[1].trim()
                if (key != "EXAMPLE" && !key.startsWith("SECTION")) {
                    val rawVal = assignMatch.groupValues[2].trim()
                    val isRequired = pendingComments.any { it.contains("[必填]") }

                    var defaultValue: String? = null
                    for (comment in pendingComments) {
                        if (comment.contains("預設值：") || comment.contains("預設值:")) {
                            val rawDefault =
                                if (comment.contains("預設值：")) {
                                    comment.substringAfter("預設值：").trim()
                                } else {
                                    comment.substringAfter("預設值:").trim()
                                }
                            defaultValue = ConfigLoader.parseDefaultValue(rawDefault)
                            break
                        }
                    }
                    if (defaultValue == null && !rawVal.startsWith("your_") && rawVal.isNotEmpty()) {
                        defaultValue = rawVal.trim('"', '\'')
                    }

                    val blockLines =
                        if (pendingComments.isNotEmpty()) {
                            pendingComments + line
                        } else {
                            listOf(line)
                        }

                    blocks.add(
                        EnvExampleBlock(
                            key = key,
                            isRequired = isRequired,
                            defaultValue = defaultValue,
                            documentation = pendingComments.joinToString("\n"),
                            rawBlockText = blockLines.joinToString("\n"),
                        ),
                    )
                    pendingComments.clear()
                    continue
                }
            }

            if (trimmed.startsWith("#")) {
                pendingComments.add(line)
            }
        }

        return blocks
    }

    fun extractDeclaredKeys(content: String): Set<String> {
        val keys = mutableSetOf<String>()
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            val match = VAR_ASSIGN_REGEX.find(trimmed)
            if (match != null) {
                val key = match.groupValues[1].trim()
                if (key != "EXAMPLE" && !key.startsWith("SECTION")) {
                    keys.add(key)
                }
            }
        }
        return keys
    }

    fun auditEnvFile(
        envFile: File,
        envExampleContent: String? = null,
    ): EnvAuditReport {
        if (!envFile.exists()) {
            throw IllegalArgumentException("Target environment file '${envFile.path}' does not exist")
        }

        val exampleText = envExampleContent ?: ConfigLoader.loadEnvExample()
        val schemaList =
            if (!exampleText.isNullOrBlank()) {
                val parsed = ConfigLoader.parseEnvExampleSchema(exampleText)
                if (parsed.isNotEmpty()) parsed else ConfigLoader.loadEnvExampleSchema()
            } else {
                ConfigLoader.loadEnvExampleSchema()
            }

        val activeConfig =
            try {
                ConfigLoader.parseDotenv(envFile)
            } catch (e: Exception) {
                emptyMap<String, String>()
            }

        val schemaKeys = schemaList.map { it.name }.toSet()

        val missingRequired = mutableListOf<String>()
        for (item in schemaList.filter { it.isRequired }) {
            val value = ConfigLoader.resolveVariableValue(item.name, activeConfig)
            if (value == null) {
                missingRequired.add(item.name)
            }
        }

        val missingOptional = mutableListOf<Pair<String, String?>>()
        for (item in schemaList.filter { !it.isRequired }) {
            val value = ConfigLoader.resolveVariableValue(item.name, activeConfig)
            if (value == null) {
                missingOptional.add(item.name to item.defaultValue)
            }
        }

        val unknownKeys = mutableListOf<String>()
        for (k in activeConfig.keys) {
            if (!ConfigLoader.isRecognizedKey(k, schemaKeys)) {
                unknownKeys.add(k)
            }
        }

        return EnvAuditReport(
            targetFile = envFile,
            missingRequired = missingRequired,
            missingOptional = missingOptional,
            unknownKeys = unknownKeys,
        )
    }

    fun syncEnvFile(
        envFile: File,
        envExampleContent: String? = null,
    ): EnvSyncResult {
        if (!envFile.exists()) {
            throw IllegalArgumentException("Target environment file '${envFile.path}' does not exist")
        }
        if (!envFile.canWrite()) {
            throw java.io.IOException("Cannot write to environment file '${envFile.path}' (Permission denied or read-only)")
        }

        val exampleText =
            envExampleContent ?: ConfigLoader.loadEnvExample()
                ?: throw IllegalStateException("Canonical .env.example content could not be resolved")

        val blocks = parseEnvExampleBlocks(exampleText)
        val fileContent = envFile.readText()
        val declaredInTarget = extractDeclaredKeys(fileContent)

        val missingBlocks = blocks.filter { it.key !in declaredInTarget }
        if (missingBlocks.isEmpty()) {
            return EnvSyncResult(
                targetFile = envFile,
                backupFile = null,
                appendedKeys = emptyList(),
                alreadyInSync = true,
            )
        }

        // 1. Create backup before modifying
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
        val parentDir = envFile.parentFile ?: File(".")
        var backupFile = File(parentDir, "${envFile.name}.bak.$timestamp")
        if (backupFile.exists()) {
            backupFile = File(parentDir, "${envFile.name}.bak.${timestamp}_${System.currentTimeMillis() % 1000}")
        }
        envFile.copyTo(backupFile, overwrite = true)

        // 2. Safely append missing variables with documentation comments
        val builder = StringBuilder(fileContent)
        if (fileContent.isNotEmpty() && !fileContent.endsWith("\n")) {
            builder.append("\n")
        }

        builder.append("\n# ==============================================================================\n")
        builder.append("# Synced from .env.example at ${LocalDateTime.now()}\n")
        builder.append("# ==============================================================================\n")

        for (block in missingBlocks) {
            builder.append("\n")
            builder.append(block.rawBlockText)
            builder.append("\n")
        }

        envFile.writeText(builder.toString())

        return EnvSyncResult(
            targetFile = envFile,
            backupFile = backupFile,
            appendedKeys = missingBlocks.map { it.key },
            alreadyInSync = false,
        )
    }

    fun handleCli(
        args: Array<String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err,
        exitProcess: (Int) -> Unit = { kotlin.system.exitProcess(it) },
    ): Boolean {
        val isCheck = args.contains("--check-env") || args.contains("--check")
        val isSync = args.contains("--sync-env") || args.contains("--sync")

        if (!isCheck && !isSync) {
            return false
        }

        val envFile = resolveEnvFile(args)
        val exampleFile = resolveExampleFile(args)
        val exampleContent = exampleFile?.takeIf { it.exists() }?.readText()

        if (isCheck) {
            try {
                if (!envFile.exists()) {
                    err.println("❌ Error: Environment file '${envFile.path}' does not exist.")
                    exitProcess(1)
                    return true
                }

                val report = auditEnvFile(envFile, exampleContent)
                out.println(report.formatVisualReport())

                if (report.isValid) {
                    exitProcess(0)
                } else {
                    exitProcess(1)
                }
            } catch (e: Exception) {
                err.println("❌ Error during environment audit: ${e.message}")
                exitProcess(1)
            }
            return true
        }

        if (isSync) {
            try {
                if (!envFile.exists()) {
                    err.println("❌ Error: Target environment file '${envFile.path}' does not exist.")
                    exitProcess(1)
                    return true
                }

                val result = syncEnvFile(envFile, exampleContent)
                out.println(result.formatSummary())
                exitProcess(0)
            } catch (e: Exception) {
                err.println("❌ Error during environment synchronization: ${e.message}")
                exitProcess(1)
            }
            return true
        }

        return false
    }
}
