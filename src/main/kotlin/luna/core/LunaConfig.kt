package luna.core

import luna.plugin.DefaultPluginContext
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Dedicated exception thrown when Luna core configuration fails validation at startup.
 * Extends [IllegalStateException] for backwards compatibility and standard runtime contract.
 */
class ConfigValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal fun failValidation(message: String): Nothing {
    JsonLogger.error(
        layer = "CONFIG",
        component = "LunaHost",
        operation = "startupValidation",
        data = emptyMap<String, Any?>(),
        errorMessage = message,
    )
    throw ConfigValidationException(message)
}

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
 * Result of a startup drift audit against the canonical environment schema.
 */
data class DriftAuditResult(
    val missingRequired: List<String>,
    val missingOptional: Map<String, Any?>,
    val unknownKeys: List<String>,
    val activeConfig: Map<String, Any?>,
)

/**
 * Navigation Pointer:
 * Configuration parsing (parseDotenv), secret masking (maskConfig, isSensitiveKey),
 * and port validation rules are coordinated with the decoupled :mcp-server module in
 * [McpServerConfig](file:///home/user/IdeaProjects/Luna/mcp-server/src/main/kotlin/luna/mcp/server/McpServerConfig.kt).
 * Any changes to masking patterns or parsing behavior must be synchronized across both modules.
 * See also: [configuration.md](file:///home/user/IdeaProjects/Luna/docs/architecture/configuration.md).
 */
data class LunaCoreConfig(
    val botToken: String,
    val port: Int = DEFAULT_PORT,
    val baseUrl: String = DEFAULT_BASE_URL,
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String = defaultRedirectUri(baseUrl),
) {
    init {
        if (botToken.isBlank()) {
            failValidation("Missing required configuration: DISCORD_TOKEN must not be blank")
        }
        if (clientId.isBlank()) {
            failValidation("Missing required configuration: DISCORD_CLIENT_ID (or DISCORD_APP_ID) must not be blank")
        }
        if (clientSecret.isBlank()) {
            failValidation("Missing required configuration: DISCORD_CLIENT_SECRET must not be blank")
        }
        if (port !in 1..65535) {
            failValidation("Invalid configuration: PORT must be between 1 and 65535, but got: $port")
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_BASE_URL = "http://localhost:8080/s/"

        fun defaultRedirectUri(baseUrl: String): String {
            val appBase =
                if (baseUrl.endsWith("/s/")) {
                    baseUrl.removeSuffix("/s/")
                } else {
                    baseUrl.trimEnd('/')
                }
            return "$appBase/api/auth/discord/callback"
        }

        fun fromMap(config: Map<String, Any?>): LunaCoreConfig {
            val token =
                (config["DISCORD_TOKEN"] ?: config["botToken"] ?: config["luna.botToken"])?.toString()
            if (token.isNullOrBlank()) {
                failValidation("Missing required configuration: DISCORD_TOKEN must not be blank")
            }

            val clientId =
                (
                    config["DISCORD_CLIENT_ID"]
                        ?: config["DISCORD_APP_ID"]
                        ?: config["clientId"]
                        ?: config["luna.clientId"]
                )?.toString()
            if (clientId.isNullOrBlank()) {
                failValidation("Missing required configuration: DISCORD_CLIENT_ID (or DISCORD_APP_ID) must not be blank")
            }

            val clientSecret =
                (
                    config["DISCORD_CLIENT_SECRET"]
                        ?: config["clientSecret"]
                        ?: config["luna.clientSecret"]
                )?.toString()
            if (clientSecret.isNullOrBlank()) {
                failValidation("Missing required configuration: DISCORD_CLIENT_SECRET must not be blank")
            }

            val rawPort = config["PORT"] ?: config["port"] ?: config["luna.port"]
            val port =
                when (rawPort) {
                    null -> {
                        DEFAULT_PORT
                    }

                    is Number -> {
                        rawPort.toInt()
                    }

                    is String -> {
                        if (rawPort.isBlank()) {
                            DEFAULT_PORT
                        } else {
                            rawPort.trim().toIntOrNull()
                                ?: failValidation("Invalid configuration: PORT must be an integer, but got '$rawPort'")
                        }
                    }

                    else -> {
                        rawPort.toString().trim().toIntOrNull()
                            ?: failValidation("Invalid configuration: PORT must be an integer, but got '$rawPort'")
                    }
                }
            if (port !in 1..65535) {
                failValidation("Invalid configuration: PORT must be between 1 and 65535, but got: $port")
            }

            val rawBaseUrl =
                (config["BASE_URL"] ?: config["baseUrl"] ?: config["curtly.baseUrl"] ?: config["luna.baseUrl"])?.toString()
            val baseUrl = if (rawBaseUrl.isNullOrBlank()) DEFAULT_BASE_URL else rawBaseUrl

            val rawRedirect =
                (
                    config["DISCORD_REDIRECT_URI"]
                        ?: config["redirectUri"]
                        ?: config["REDIRECT_URI"]
                        ?: config["luna.redirectUri"]
                )?.toString()
            val redirectUri = if (rawRedirect.isNullOrBlank()) defaultRedirectUri(baseUrl) else rawRedirect

            return LunaCoreConfig(
                botToken = token,
                port = port,
                baseUrl = baseUrl,
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = redirectUri,
            )
        }
    }
}

/**
 * Configuration loader implementing layered precedence:
 * application.yml placeholders > System.getenv() > .env file key-values.
 */
object ConfigLoader {
    private val SENSITIVE_EXPLICIT_KEYS =
        setOf(
            "DISCORD_TOKEN",
            "DISCORD_CLIENT_SECRET",
            "GEMINI_API_KEY",
            "YOUTUBE_API_KEY",
            "MCP_AUTH_TOKEN",
            "MCP_OAUTH_CLIENT_SECRET",
            "clientSecret",
            "staticAuthToken",
        )

    private val PLACEHOLDER_REGEX = Regex("""\$\{\??([^}:]+)(?::([^}]*))?\}""")

    /**
     * Checks if the configuration key contains sensitive information that must be masked.
     */
    fun isSensitiveKey(key: String): Boolean {
        val upper = key.uppercase()
        return upper in SENSITIVE_EXPLICIT_KEYS ||
            upper.endsWith("_SECRET") ||
            upper.endsWith("_TOKEN") ||
            upper.endsWith("_KEY") ||
            upper.endsWith(".SECRET") ||
            upper.endsWith(".TOKEN") ||
            upper.endsWith(".KEY") ||
            upper == "SECRET" ||
            upper == "TOKEN" ||
            upper == "KEY" ||
            key.endsWith("Secret") ||
            key.endsWith("Token") ||
            key.endsWith("Key")
    }

    /**
     * Masks sensitive configuration values with '******'.
     */
    fun maskConfig(config: Map<String, Any?>): Map<String, Any?> =
        config.mapValues { (key, value) ->
            maskValue(key, value)
        }

    fun maskValue(
        key: String,
        value: Any?,
    ): Any? {
        if (value == null) return null
        if (isSensitiveKey(key)) return "******"
        if (value is Map<*, *>) {
            return value.entries.associate { (k, v) ->
                val nestedKey = k.toString()
                nestedKey to maskValue(nestedKey, v)
            }
        }
        return value
    }

    /**
     * Emits structured JSON audit log displaying active configurations with sensitive values masked.
     */
    fun auditLog(config: Map<String, Any?>) {
        val masked = maskConfig(config)
        JsonLogger.log(
            layer = "CONFIG",
            component = "LunaHost",
            operation = "startupAudit",
            data = masked,
        )
    }

    /**
     * Parses a .env format string or sequence into a key-value map.
     */
    fun parseDotenv(lines: Sequence<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    var value = parts[1].trim()
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))
                    ) {
                        value = value.substring(1, value.length - 1)
                    }
                    result[key] = value
                }
            }
        }
        return result
    }

    fun parseDotenv(content: String): Map<String, String> = parseDotenv(content.lineSequence())

    fun parseDotenv(file: File): Map<String, String> = file.useLines { lines -> parseDotenv(lines) }

    /**
     * Resolves `${VAR:default}` or `${?VAR}` placeholders in string values against the current config and system environment.
     */
    fun resolvePlaceholders(
        raw: String,
        config: Map<String, Any?>,
    ): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("\${") && trimmed.endsWith("}")) {
            val inner = trimmed.substring(2, trimmed.length - 1).trim()
            val cleanInner = if (inner.startsWith("?")) inner.substring(1) else inner
            val parts = cleanInner.split(":", limit = 2)
            val envVarName = parts[0].trim()
            val defaultValue = if (parts.size > 1) parts[1].trim() else ""
            val resolvedVal =
                (config[envVarName] as? String)
                    ?: (config[envVarName]?.toString())
                    ?: System.getenv(envVarName)
            return if (!resolvedVal.isNullOrEmpty()) resolvedVal else defaultValue
        }

        if (raw.contains("\${")) {
            return PLACEHOLDER_REGEX.replace(raw) { matchResult ->
                val envVarName = matchResult.groupValues[1].trim()
                val defaultValue = if (matchResult.groupValues.size > 2) matchResult.groupValues[2].trim() else ""
                val resolvedVal =
                    (config[envVarName] as? String)
                        ?: (config[envVarName]?.toString())
                        ?: System.getenv(envVarName)
                if (!resolvedVal.isNullOrEmpty()) resolvedVal else defaultValue
            }
        }

        return raw
    }

    /**
     * Recursively flattens nested YAML maps and resolves placeholders against accumulated config.
     */
    fun flattenYaml(
        prefix: String,
        map: Map<*, *>,
        target: MutableMap<String, Any?>,
    ) {
        for ((key, value) in map) {
            val fullKey = if (prefix.isEmpty()) key.toString() else "$prefix.$key"
            when (value) {
                is Map<*, *> -> {
                    target[fullKey] = value
                    flattenYaml(fullKey, value, target)
                }

                is String -> {
                    val resolved = resolvePlaceholders(value, target)
                    target[fullKey] = resolved
                }

                else -> {
                    if (value != null) {
                        target[fullKey] = value
                    }
                }
            }
        }
    }

    /**
     * Loads configuration across three layers with strict precedence:
     * application.yml placeholders > System.getenv() > .env file key-values.
     */
    fun loadConfig(
        customConfig: Map<String, Any?>? = null,
        envFile: File = File(".env"),
        systemEnv: Map<String, String> = System.getenv(),
        yamlStreamProvider: (() -> InputStream?)? = null,
    ): Map<String, Any?> {
        val config = mutableMapOf<String, Any?>()

        // 1. Ingest .env if present
        if (envFile.exists()) {
            try {
                config.putAll(parseDotenv(envFile))
            } catch (e: Exception) {
                JsonLogger.error(
                    layer = "CONFIG",
                    component = "LunaHost",
                    operation = "loadConfig",
                    data = emptyMap<String, Any?>(),
                    errorMessage = "Failed to parse .env: ${e.message}",
                )
            }
        }

        // 2. Ingest System.getenv()
        config.putAll(systemEnv)

        // 3. Ingest application.yml
        val yamlStream: InputStream? =
            if (yamlStreamProvider != null) {
                yamlStreamProvider()
            } else {
                val yamlFile = File("application.yml")
                if (yamlFile.exists()) {
                    yamlFile.inputStream()
                } else {
                    Thread.currentThread().contextClassLoader.getResourceAsStream("application.yml")
                }
            }

        yamlStream?.use { stream ->
            try {
                val yaml = Yaml()
                val yamlMap = yaml.load<Map<String, Any?>>(stream)
                if (yamlMap != null) {
                    flattenYaml("", yamlMap, config)
                }
            } catch (e: Exception) {
                JsonLogger.error(
                    layer = "CONFIG",
                    component = "LunaHost",
                    operation = "loadConfig",
                    data = emptyMap<String, Any?>(),
                    errorMessage = "Failed to parse application.yml: ${e.message}",
                )
            }
        }

        // 4. Ingest customConfig overrides if specified
        if (customConfig != null) {
            config.putAll(customConfig)
        }

        return config
    }

    /**
     * In-memory source loader for deterministic unit testing of precedence layers.
     */
    fun loadConfigFromSources(
        dotenvContent: String? = null,
        systemEnv: Map<String, String> = emptyMap(),
        yamlContent: String? = null,
        customConfig: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val config = mutableMapOf<String, Any?>()
        if (dotenvContent != null) {
            config.putAll(parseDotenv(dotenvContent))
        }
        config.putAll(systemEnv)
        if (yamlContent != null) {
            val yaml = Yaml()
            val yamlMap = yaml.load<Map<String, Any?>>(ByteArrayInputStream(yamlContent.toByteArray()))
            if (yamlMap != null) {
                flattenYaml("", yamlMap, config)
            }
        }
        if (customConfig != null) {
            config.putAll(customConfig)
        }
        return config
    }

    /**
     * Loads and validates the core configuration, throwing [ConfigValidationException] if invalid.
     */
    fun loadCoreConfig(customConfig: Map<String, Any?>? = null): LunaCoreConfig {
        val config = loadConfig(customConfig)
        auditDrift(config)
        return LunaCoreConfig.fromMap(config)
    }

    val STANDARD_SYSTEM_KEYS =
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

    val CORE_KNOWN_ALIASES =
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
            "mcp.clientId" to "MCP_OAUTH_CLIENT_ID",
            "MCP_CLIENT_ID" to "MCP_OAUTH_CLIENT_ID",
            "mcp.clientSecret" to "MCP_OAUTH_CLIENT_SECRET",
            "MCP_CLIENT_SECRET" to "MCP_OAUTH_CLIENT_SECRET",
            "staticAuthToken" to "MCP_AUTH_TOKEN",
            "mcp.staticAuthToken" to "MCP_AUTH_TOKEN",
            "requireAuth" to "MCP_REQUIRE_AUTH",
            "mcp.requireAuth" to "MCP_REQUIRE_AUTH",
            "authExpected" to "MCP_REQUIRE_AUTH",
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

    private fun defaultFallbackSchema(): List<EnvVarSchema> =
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
            EnvVarSchema("MCP_DB_PATH", isRequired = false, defaultValue = "./data/urls.db", section = 5),
            EnvVarSchema("MCP_PUBLIC_BASE_URL", isRequired = false, defaultValue = "https://woodendoor.website", section = 5),
            EnvVarSchema("MCP_OAUTH_CLIENT_ID", isRequired = false, defaultValue = "luna-mcp-client", section = 5),
            EnvVarSchema("MCP_OAUTH_CLIENT_SECRET", isRequired = false, section = 5),
            EnvVarSchema("MCP_AUTH_TOKEN", isRequired = false, section = 5),
            EnvVarSchema("MCP_REQUIRE_AUTH", isRequired = false, defaultValue = "false", section = 5),
        )

    fun loadEnvExampleSchema(): List<EnvVarSchema> {
        val text = loadEnvExample()
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

        val candidates = DefaultPluginContext.resolveCandidateKeys(key)
        return candidates.any { it in schemaKeys }
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
                "MCP_OAUTH_CLIENT_ID" -> listOf("clientId", "mcp.clientId", "MCP_CLIENT_ID")
                "MCP_OAUTH_CLIENT_SECRET" -> listOf("clientSecret", "mcp.clientSecret", "MCP_CLIENT_SECRET")
                "MCP_AUTH_TOKEN" -> listOf("staticAuthToken", "mcp.staticAuthToken")
                "MCP_REQUIRE_AUTH" -> listOf("requireAuth", "mcp.requireAuth")
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

    /**
     * Performs startup drift audit checking missing required, missing optional with defaults,
     * and unknown or deprecated variables.
     */
    fun auditDrift(
        config: Map<String, Any?>,
        envExampleContent: String? = loadEnvExample(),
        requiredKeys: Set<String>? = null,
    ): DriftAuditResult {
        val schemaList =
            if (envExampleContent != null) {
                val parsed = parseEnvExampleSchema(envExampleContent)
                if (parsed.isNotEmpty()) parsed else loadEnvExampleSchema()
            } else {
                loadEnvExampleSchema()
            }

        val schemaKeys = schemaList.map { it.name }.toSet()
        val hostSchema = schemaList.filter { it.section in 1..4 }

        // 1. Missing required variables
        val effectiveRequired = requiredKeys ?: hostSchema.filter { it.isRequired }.map { it.name }.toSet()
        val missingRequired = mutableListOf<String>()
        for (req in effectiveRequired) {
            val value = resolveVariableValue(req, config)
            if (value == null) {
                missingRequired.add(req)
            }
        }

        if (missingRequired.isNotEmpty()) {
            val errMsg = "Missing required configuration: ${missingRequired.joinToString(", ")} must not be blank"
            failValidation(errMsg)
        }

        // 2. Missing optional variables
        val missingOptional = mutableMapOf<String, Any?>()
        for (item in hostSchema.filter { !it.isRequired }) {
            val value = resolveVariableValue(item.name, config)
            if (value == null) {
                val defaultVal =
                    item.defaultValue ?: when (item.name) {
                        "PORT" -> 8080
                        "BASE_URL" -> LunaCoreConfig.DEFAULT_BASE_URL
                        "DISCORD_REDIRECT_URI" -> LunaCoreConfig.defaultRedirectUri(LunaCoreConfig.DEFAULT_BASE_URL)
                        else -> null
                    }
                if (defaultVal != null) {
                    missingOptional[item.name] = defaultVal
                    val maskedDefault = maskValue(item.name, defaultVal)
                    JsonLogger.log(
                        layer = "CONFIG",
                        component = "LunaHost",
                        operation = "startupAudit",
                        data =
                            mapOf(
                                "component" to "LunaHost",
                                "key" to item.name,
                                "defaultValue" to maskedDefault,
                            ),
                        status = "APPLIED_DEFAULT",
                    )
                }
            }
        }

        // 3. Deprecated or unrecognized variables
        val unknownKeys = mutableListOf<String>()
        for (k in config.keys) {
            if (!isRecognizedKey(k, schemaKeys)) {
                unknownKeys.add(k)
            }
        }

        if (unknownKeys.isNotEmpty()) {
            val warnMessage = "[CONFIG_DRIFT] Unknown or deprecated environment keys: $unknownKeys"
            JsonLogger.warn(
                layer = "CONFIG",
                component = "LunaHost",
                operation = "startupDriftAudit",
                data = mapOf("unknownKeys" to unknownKeys),
                message = warnMessage,
            )
        }

        val maskedActive = maskConfig(config)
        return DriftAuditResult(
            missingRequired = missingRequired,
            missingOptional = missingOptional,
            unknownKeys = unknownKeys,
            activeConfig = maskedActive,
        )
    }

    /**
     * Loads the canonical .env.example specification as an InputStream from classpath resources or filesystem.
     */
    fun getEnvExampleStream(): InputStream? =
        Thread.currentThread().contextClassLoader.getResourceAsStream(".env.example")
            ?: ConfigLoader::class.java.getResourceAsStream("/.env.example")
            ?: File(".env.example").takeIf { it.exists() && it.isFile }?.inputStream()

    /**
     * Reads the canonical .env.example specification content as a String.
     */
    fun loadEnvExample(): String? = getEnvExampleStream()?.bufferedReader()?.use { it.readText() }
}
