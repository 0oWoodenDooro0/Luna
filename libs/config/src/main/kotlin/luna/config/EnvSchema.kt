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

    val CORE_KNOWN_ALIASES: Map<String, String> =
        mapOf(
            "DISCORD_APP_ID" to "DISCORD_CLIENT_ID",
            "clientId" to "DISCORD_CLIENT_ID",
            "luna.clientId" to "DISCORD_CLIENT_ID",
            "botToken" to "DISCORD_TOKEN",
            "luna.botToken" to "DISCORD_TOKEN",
            "clientSecret" to "DISCORD_CLIENT_SECRET",
            "luna.clientSecret" to "DISCORD_CLIENT_SECRET",
            "port" to "PORT",
            "luna.port" to "PORT",
            "MCP_PORT" to "PORT",
            "mcp.port" to "MCP_PORT",
            "baseUrl" to "BASE_URL",
            "luna.baseUrl" to "BASE_URL",
            "curtly.baseUrl" to "BASE_URL",
            "curtly.base_url" to "BASE_URL",
            "CURTLY_BASE_URL" to "BASE_URL",
            "PLUGINS_CURTLY_BASE_URL" to "BASE_URL",
            "redirectUri" to "DISCORD_REDIRECT_URI",
            "REDIRECT_URI" to "DISCORD_REDIRECT_URI",
            "luna.redirectUri" to "DISCORD_REDIRECT_URI",
            "dbPath" to "MCP_DB_PATH",
            "mcp.dbPath" to "MCP_DB_PATH",
            "publicBaseUrl" to "MCP_PUBLIC_BASE_URL",
            "mcp.publicBaseUrl" to "MCP_PUBLIC_BASE_URL",
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
            EnvVarSchema("PLUGINS_DISABLED", isRequired = false, section = 4),
            EnvVarSchema("PLUGINS_CURTLY_ENABLED", isRequired = false, defaultValue = "true", section = 4),
            EnvVarSchema("PLUGINS_MCP_ENABLED", isRequired = false, defaultValue = "true", section = 4),
            EnvVarSchema("PLUGINS_SOCIAL_PREVIEW_ENABLED", isRequired = false, defaultValue = "true", section = 4),
            EnvVarSchema("PLUGINS_SUMMARIZER_ENABLED", isRequired = false, defaultValue = "true", section = 4),
            EnvVarSchema("PLUGINS_UNDERCOVER_ENABLED", isRequired = false, defaultValue = "true", section = 4),
            EnvVarSchema("MCP_PORT", isRequired = false, defaultValue = "8081", section = 5),
            EnvVarSchema("MCP_DB_PATH", isRequired = false, defaultValue = "./.data/urls.db", section = 5),
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
        if (key in CORE_KNOWN_ALIASES) return true
        if (key in STANDARD_SYSTEM_KEYS) return true
        if (key.startsWith("PLUGINS_") && key.endsWith("_ENABLED")) return true
        if (key.startsWith("plugins.") && key.endsWith(".enabled")) return true
        if (key.startsWith("curtly.") || key.startsWith("CURTLY_")) return true
        if (key.startsWith("summarizer.") || key.startsWith("summary.") || key.startsWith("SUMMARY_")) return true
        if (key.startsWith("gemini.") || key.startsWith("GEMINI_")) return true
        if (key.startsWith("youtube.") || key.startsWith("YOUTUBE_")) return true
        if (key.startsWith("undercover.") || key.startsWith("UNDERCOVER_")) return true
        if (key.startsWith("mcp.") || key.startsWith("MCP_")) return true

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
        if (exact != null && exact.toString().isNotBlank()) return exact

        val aliases =
            when (key) {
                "DISCORD_TOKEN" -> listOf("botToken", "luna.botToken")
                "DISCORD_CLIENT_ID" -> listOf("DISCORD_APP_ID", "clientId", "luna.clientId")
                "DISCORD_CLIENT_SECRET" -> listOf("clientSecret", "luna.clientSecret")
                "PORT" -> listOf("port", "luna.port", "MCP_PORT", "mcp.port")
                "BASE_URL" -> listOf("baseUrl", "curtly.baseUrl", "curtly.base_url", "CURTLY_BASE_URL", "PLUGINS_CURTLY_BASE_URL", "luna.baseUrl")
                "DISCORD_REDIRECT_URI" -> listOf("redirectUri", "REDIRECT_URI", "luna.redirectUri")
                "SUMMARY_EMOJI" -> listOf("summarizer.emoji", "summary.emoji")
                "SUMMARY_MAX_HISTORY_MESSAGES" -> listOf("summary.maxHistoryMessages", "summarizer.maxHistoryMessages")
                "SUMMARY_INACTIVITY_GAP_MINUTES" -> listOf("summary.inactivityGapMinutes", "summarizer.inactivityGapMinutes")
                "SUMMARY_MAX_CHARACTER_BUDGET" -> listOf("summary.maxCharacterBudget", "summarizer.maxCharacterBudget")
                "SUMMARY_MAX_LOOKBACK_MESSAGES" -> listOf("summary.maxLookbackMessages", "summarizer.maxLookbackMessages")
                "SUMMARY_MAX_IMAGES" -> listOf("summary.maxImages", "summarizer.maxImages")
                "GEMINI_API_KEY" -> listOf("gemini.apiKey", "gemini.api_key")
                "GEMINI_MODEL" -> listOf("gemini.model")
                "GEMINI_BASE_URL" -> listOf("gemini.baseUrl", "gemini.base_url")
                "YOUTUBE_API_KEY" -> listOf("youtube.apiKey", "youtube.api_key")
                "MCP_PORT" -> listOf("PORT", "port", "mcp.port")
                "MCP_DB_PATH" -> listOf("dbPath", "mcp.dbPath")
                "MCP_PUBLIC_BASE_URL" -> listOf("publicBaseUrl", "mcp.publicBaseUrl")
                else -> emptyList()
            }

        for (alias in aliases) {
            val aliasVal = config[alias]
            if (aliasVal != null && aliasVal.toString().isNotBlank()) return aliasVal
        }

        for ((alias, canonical) in CORE_KNOWN_ALIASES) {
            if (canonical == key) {
                val aliasVal = config[alias]
                if (aliasVal != null && aliasVal.toString().isNotBlank()) return aliasVal
            }
        }

        return null
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
        for (key in config.keys) {
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
