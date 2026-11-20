package luna.config

import java.io.File
import java.io.InputStream

/**
 * Specification schema for an environment variable parsed from .env.example.
 */
data class EnvVarSchema(
    val name: String,
    val isRequired: Boolean,
    val defaultValue: String? = null,
    val section: Int = 0,
    val description: String? = null,
)

/**
 * Result of a startup drift audit against canonical environment schema.
 */
data class DriftAuditResult(
    val missingRequired: List<String>,
    val missingOptional: Map<String, Any?>,
    val unknownKeys: List<String>,
    val activeConfig: Map<String, Any?>,
)

/**
 * Authoritative schema parsing and drift auditing engine.
 */
object EnvSchemaAuditor {
    val STANDARD_SYSTEM_KEYS: Set<String> =
        setOf(
            "PATH",
            "HOME",
            "USER",
            "LOGNAME",
            "SHELL",
            "PWD",
            "OLDPWD",
            "TERM",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "JAVA_HOME",
            "JAVA_VERSION",
            "LANGUAGE",
            "HOSTNAME",
            "SHLVL",
            "_",
            "LS_COLORS",
            "EDITOR",
            "PAGER",
            "TZ",
            "NODE_VERSION",
            "CI",
            "CLASSPATH",
        )

    fun parseDefaultValue(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("空值")) return null
        if (trimmed.contains("由 BASE_URL 自動推導為 ")) {
            return trimmed.substringAfter("由 BASE_URL 自動推導為 ").trim()
        }
        if (trimmed.contains(" ")) {
            val firstWord = trimmed.substringBefore(" ").trim()
            if (firstWord.equals("true", ignoreCase = true) || firstWord.equals("false", ignoreCase = true)) {
                return firstWord
            }
        }
        return trimmed
    }

    fun parseEnvExampleSchema(content: String): List<EnvVarSchema> {
        if (content.isBlank()) return emptyList()

        val result = mutableListOf<EnvVarSchema>()
        var currentSection = 0
        var isRequired = false
        var defaultValue: String? = null
        var description: String? = null

        val sectionRegex = Regex("""(?i)Section\s+(\d+)""")
        val defaultValRegex = Regex("""^#\s*預設值[：:]\s*(.*)$""")
        val descRegex = Regex("""^#\s*說明[：:]\s*(.*)$""")
        val varAssignRegex = Regex("""^(?:#\s*)?([A-Z0-9_]{3,})\s*=\s*(.*)$""")

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val sectionMatch = sectionRegex.find(trimmed)
            if (sectionMatch != null && trimmed.startsWith("#")) {
                currentSection = sectionMatch.groupValues[1].toIntOrNull() ?: currentSection
                continue
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.contains("[必填]")) {
                    isRequired = true
                } else if (trimmed.contains("[選填]")) {
                    isRequired = false
                }

                val defaultMatch = defaultValRegex.find(trimmed)
                if (defaultMatch != null) {
                    val rawDefault = defaultMatch.groupValues[1].trim()
                    defaultValue = parseDefaultValue(rawDefault)
                }

                val descMatch = descRegex.find(trimmed)
                if (descMatch != null) {
                    description = descMatch.groupValues[1].trim()
                }
            }

            val assignMatch = varAssignRegex.find(trimmed)
            if (assignMatch != null) {
                val key = assignMatch.groupValues[1].trim()
                if (key != "EXAMPLE" && !key.startsWith("SECTION")) {
                    val rawVal = assignMatch.groupValues[2].trim()
                    if (defaultValue == null && !rawVal.startsWith("your_") && rawVal.isNotEmpty()) {
                        defaultValue = rawVal.trim('\"', '\'')
                    }

                    result.add(
                        EnvVarSchema(
                            name = key,
                            isRequired = isRequired,
                            defaultValue = defaultValue,
                            section = currentSection,
                            description = description,
                        ),
                    )
                    isRequired = false
                    defaultValue = null
                    description = null
                }
            }
        }
        return result
    }

    fun defaultFallbackSchema(): List<EnvVarSchema> =
        listOf(
            EnvVarSchema("DISCORD_TOKEN", isRequired = true, section = 1),
            EnvVarSchema("DISCORD_CLIENT_ID", isRequired = true, section = 1),
            EnvVarSchema("DISCORD_CLIENT_SECRET", isRequired = true, section = 1),
            EnvVarSchema("DISCORD_REDIRECT_URI", isRequired = false, defaultValue = "http://localhost:8080/api/auth/discord/callback", section = 1),
            EnvVarSchema("PORT", isRequired = false, defaultValue = "8080", section = 1),
            EnvVarSchema("ERROR_NOTIFY_DISCORD_USER_IDS", isRequired = false, section = 1),
            EnvVarSchema("ERROR_NOTIFY_ENV", isRequired = false, section = 1),
            EnvVarSchema("ERROR_NOTIFY_COOLDOWN_SECONDS", isRequired = false, defaultValue = "300", section = 1),
            EnvVarSchema("BASE_URL", isRequired = false, defaultValue = "http://localhost:8080/s/", section = 2),
            EnvVarSchema("GEMINI_API_KEY", isRequired = false, section = 3),
            EnvVarSchema("GEMINI_MODEL", isRequired = false, defaultValue = "gemini-3.5-flash-lite", section = 3),
            EnvVarSchema("GEMINI_BASE_URL", isRequired = false, defaultValue = "https://generativelanguage.googleapis.com", section = 3),
            EnvVarSchema("YOUTUBE_API_KEY", isRequired = false, section = 3),
            EnvVarSchema("SUMMARY_EMOJI", isRequired = false, defaultValue = "📝", section = 3),
            EnvVarSchema("SUMMARY_MAX_HISTORY_MESSAGES", isRequired = false, defaultValue = "50", section = 3),
            EnvVarSchema("SUMMARY_INACTIVITY_GAP_MINUTES", isRequired = false, defaultValue = "15", section = 3),
            EnvVarSchema("SUMMARY_MAX_CHARACTER_BUDGET", isRequired = false, defaultValue = "20000", section = 3),
            EnvVarSchema("SUMMARY_MAX_LOOKBACK_MESSAGES", isRequired = false, defaultValue = "20", section = 3),
            EnvVarSchema("SUMMARY_MAX_IMAGES", isRequired = false, defaultValue = "10", section = 3),
            EnvVarSchema("CHATTER_GEMINI_MODEL", isRequired = false, defaultValue = "gemini-3.5-flash-lite", section = 3),
            EnvVarSchema("CHATTER_EMOJI_VISION_MODEL", isRequired = false, section = 3),
            EnvVarSchema("CHATTER_DECISION_MODEL", isRequired = false, defaultValue = "gemini-decision-1", section = 3),
            EnvVarSchema("CHATTER_DECISION_THRESHOLD", isRequired = false, defaultValue = "0.7", section = 3),
            EnvVarSchema("CHATTER_DECISION_TIMEOUT_SECONDS", isRequired = false, defaultValue = "15", section = 3),
            EnvVarSchema("CHATTER_DECISION_INSTRUCTIONS", isRequired = false, section = 3),
            EnvVarSchema("CHATTER_DECISION_MESSAGE_COUNT", isRequired = false, defaultValue = "15", section = 3),
            EnvVarSchema("CHATTER_ENABLED", isRequired = false, defaultValue = "true", section = 3),
            EnvVarSchema("CHATTER_ALLOWED_CHANNELS", isRequired = false, section = 3),
            EnvVarSchema("CHATTER_COOLDOWN_SECONDS", isRequired = false, defaultValue = "5", section = 3),
            EnvVarSchema("CHATTER_DEBOUNCE_SECONDS", isRequired = false, defaultValue = "2.5", section = 3),
            EnvVarSchema("CHATTER_MENTION_DEBOUNCE_SECONDS", isRequired = false, defaultValue = "1.2", section = 3),
            EnvVarSchema("CHATTER_DEBOUNCE_MAX_SECONDS", isRequired = false, defaultValue = "7.0", section = 3),
            EnvVarSchema("CHATTER_MAX_INTERRUPTS", isRequired = false, defaultValue = "2", section = 3),
            EnvVarSchema("CHATTER_TIMEZONE", isRequired = false, defaultValue = "Asia/Taipei", section = 3),
            EnvVarSchema("CHATTER_DRIFT_ENABLED", isRequired = false, defaultValue = "true", section = 3),
            EnvVarSchema("CHATTER_REACTION_THRESHOLD", isRequired = false, defaultValue = "0.45", section = 3),
            EnvVarSchema("CHATTER_HISTORY_WINDOW_MINUTES", isRequired = false, defaultValue = "10", section = 3),
            EnvVarSchema("CHATTER_HISTORY_MESSAGE_COUNT", isRequired = false, defaultValue = "30", section = 3),
            EnvVarSchema("CHATTER_MEMORY_ENABLED", isRequired = false, defaultValue = "true", section = 3),
            EnvVarSchema("CHATTER_MEMORY_DISTILL_MODEL", isRequired = false, defaultValue = "gemini-3.5-flash-lite", section = 3),
            EnvVarSchema("CHATTER_MEMORY_EMBEDDING_MODEL", isRequired = false, defaultValue = "gemini-embedding-001", section = 3),
            EnvVarSchema("CHATTER_MEMORY_QUIET_MINUTES", isRequired = false, defaultValue = "5", section = 3),
            EnvVarSchema("CHATTER_MEMORY_MIN_MESSAGES", isRequired = false, defaultValue = "5", section = 3),
            EnvVarSchema("CHATTER_MEMORY_MAX_BATCH_MESSAGES", isRequired = false, defaultValue = "50", section = 3),
            EnvVarSchema("CHATTER_MEMORY_MAX_BATCH_AGE_MINUTES", isRequired = false, defaultValue = "30", section = 3),
            EnvVarSchema("CHATTER_MEMORY_RECENCY_EXCLUDE_MINUTES", isRequired = false, defaultValue = "10", section = 3),
            EnvVarSchema("CHATTER_MEMORY_SCORE_CUTOFF", isRequired = false, defaultValue = "0.55", section = 3),
            EnvVarSchema("CHATTER_MEMORY_MAX_TOOL_CALLS", isRequired = false, defaultValue = "5", section = 3),
            EnvVarSchema("CHATTER_MEMORY_MAX_TURNS", isRequired = false, defaultValue = "8", section = 3),
            EnvVarSchema("CHATTER_MEMORY_TOOL_TIMEOUT_MS", isRequired = false, defaultValue = "3000", section = 3),
            EnvVarSchema("CHATTER_MEMORY_MAX_PER_USER", isRequired = false, defaultValue = "100", section = 3),
            EnvVarSchema("CHATTER_MEMORY_MAX_PER_CHANNEL", isRequired = false, defaultValue = "200", section = 3),
            EnvVarSchema("SQLITE_VEC_EXTENSION_PATH", isRequired = false, defaultValue = "/app/lib/vec0.so", section = 3),
            EnvVarSchema("AI_TRACE_ENABLED", isRequired = false, defaultValue = "true", section = 3),
            EnvVarSchema("AI_TRACE_DIR", isRequired = false, defaultValue = "data/logs/ai", section = 3),
            EnvVarSchema("AI_TRACE_MAX_DAYS", isRequired = false, defaultValue = "14", section = 3),
            EnvVarSchema("AI_TRACE_MAX_TOTAL_MB", isRequired = false, defaultValue = "200", section = 3),
            EnvVarSchema("PLUGINS_DISABLED", isRequired = false, section = 4),
            EnvVarSchema("PLUGINS_CURTLY_ENABLED", isRequired = false, defaultValue = "true", section = 4),
            EnvVarSchema("PLUGINS_MCP_ENABLED", isRequired = false, defaultValue = "true", section = 4),
            EnvVarSchema("PLUGINS_SOCIAL_PREVIEW_ENABLED", isRequired = false, defaultValue = "true", section = 4),
            EnvVarSchema("PLUGINS_SUMMARIZER_ENABLED", isRequired = false, defaultValue = "true", section = 4),
            EnvVarSchema("PLUGINS_UNDERCOVER_ENABLED", isRequired = false, defaultValue = "true", section = 4),
            EnvVarSchema("MCP_PORT", isRequired = false, defaultValue = "8081", section = 5),
            EnvVarSchema("MCP_DB_PATH", isRequired = false, defaultValue = "./.data/luna.db", section = 5),
            EnvVarSchema("MCP_PUBLIC_BASE_URL", isRequired = false, defaultValue = "https://woodendoor.website", section = 5),
        )

    fun loadDefaultEnvExampleStream(): InputStream? =
        Thread.currentThread().contextClassLoader.getResourceAsStream(".env.example")
            ?: EnvSchemaAuditor::class.java.getResourceAsStream("/.env.example")
            ?: ConfigFinder
                .findDefaultEnvFile()
                ?.parentFile
                ?.let { File(it, ".env.example") }
                ?.takeIf { it.exists() && it.isFile }
                ?.inputStream()
            ?: File(".env.example").takeIf { it.exists() && it.isFile }?.inputStream()

    fun loadDefaultEnvExample(): String? = loadDefaultEnvExampleStream()?.use { it.bufferedReader().readText() }

    fun loadDefaultSchema(): List<EnvVarSchema> {
        val text = loadDefaultEnvExample()
        return if (!text.isNullOrBlank()) {
            val parsed = parseEnvExampleSchema(text)
            if (parsed.isNotEmpty()) parsed else defaultFallbackSchema()
        } else {
            defaultFallbackSchema()
        }
    }

    fun isRecognizedKey(
        key: String,
        schemaKeys: Set<String>,
        candidateAliases: Map<String, List<String>> = emptyMap(),
    ): Boolean {
        if (key in schemaKeys) return true
        if (key in STANDARD_SYSTEM_KEYS) return true
        if (key.startsWith("PLUGINS_") && key.endsWith("_ENABLED")) return true

        val candidates = candidateAliases[key]
        if (candidates != null && candidates.any { it in schemaKeys }) {
            return true
        }

        return false
    }

    fun resolveVariableValue(
        key: String,
        config: Map<String, Any?>,
    ): Any? {
        val exact = config[key]
        return if (exact != null && exact.toString().isNotBlank()) exact else null
    }

    fun auditDrift(
        config: Map<String, Any?>,
        schema: List<EnvVarSchema>,
        allSchemaKeys: Set<String> = schema.map { it.name }.toSet(),
        requiredKeys: Set<String>? = null,
        fallbackDefaults: Map<String, Any?> = emptyMap(),
        candidateAliases: Map<String, List<String>> = emptyMap(),
    ): DriftAuditResult {
        val effectiveRequired = requiredKeys ?: schema.filter { it.isRequired }.map { it.name }.toSet()
        val missingRequired = mutableListOf<String>()
        for (req in effectiveRequired) {
            val value = resolveVariableValue(req, config)
            if (value == null) {
                missingRequired.add(req)
            }
        }

        val missingOptional = mutableMapOf<String, Any?>()
        for (item in schema.filter { !it.isRequired }) {
            val value = resolveVariableValue(item.name, config)
            if (value == null) {
                val defaultVal = item.defaultValue ?: fallbackDefaults[item.name]
                if (defaultVal != null) {
                    missingOptional[item.name] = defaultVal
                }
            }
        }

        val unknownKeys = mutableListOf<String>()
        for ((key, value) in config) {
            if (value is Map<*, *>) continue
            if (!isRecognizedKey(key, allSchemaKeys, candidateAliases)) {
                unknownKeys.add(key)
            }
        }

        return DriftAuditResult(
            missingRequired = missingRequired,
            missingOptional = missingOptional,
            unknownKeys = unknownKeys,
            activeConfig = SecretMasker.maskConfig(config),
        )
    }
}
