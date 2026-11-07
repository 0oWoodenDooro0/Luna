package luna.mcp.server

import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream

/**
 * Dedicated exception thrown when MCP server configuration fails validation at startup.
 * Extends [IllegalStateException] for backwards compatibility and standard runtime contracts.
 */
class McpConfigValidationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

typealias ConfigValidationException = McpConfigValidationException

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
 * and port validation rules are coordinated with the Luna bot host module in
 * [LunaConfig](file:///home/user/IdeaProjects/Luna/src/main/kotlin/luna/core/LunaConfig.kt).
 * Any changes to masking patterns or parsing behavior must be synchronized across both modules.
 * See also: [configuration.md](file:///home/user/IdeaProjects/Luna/docs/architecture/configuration.md).
 */
data class McpServerConfig(
    val port: Int = DEFAULT_PORT,
    val dbPath: String = DEFAULT_DB_PATH,
    val publicBaseUrl: String = DEFAULT_PUBLIC_BASE_URL,
    val clientId: String = DEFAULT_CLIENT_ID,
    val clientSecret: String? = null,
    val staticAuthToken: String? = null,
    val requireAuth: Boolean = false,
) {
    val normalizedBaseUrl: String = publicBaseUrl.trimEnd('/')

    val isAuthEnabled: Boolean
        get() = requireAuth || !clientSecret.isNullOrBlank() || !staticAuthToken.isNullOrBlank()

    init {
        if (port !in 1..65535) {
            throw McpConfigValidationException("Invalid configuration: PORT must be between 1 and 65535, but got: $port")
        }
        if (requireAuth && clientSecret.isNullOrBlank() && staticAuthToken.isNullOrBlank()) {
            throw McpConfigValidationException(
                "Invalid configuration: Authentication is required, but both clientSecret and staticAuthToken are blank",
            )
        }
    }

    /**
     * Converts this MCP server configuration to a compatible [McpOAuthConfig] instance.
     */
    fun toOAuthConfig(): McpOAuthConfig =
        McpOAuthConfig(
            publicBaseUrl = normalizedBaseUrl,
            clientId = clientId,
            clientSecret = clientSecret,
            staticAuthToken = staticAuthToken,
            requireAuth = requireAuth,
        )

    /**
     * Returns a representation of active configuration with sensitive values masked.
     */
    fun toMaskedMap(): Map<String, Any?> =
        mapOf(
            "MCP_PORT" to port,
            "MCP_DB_PATH" to dbPath,
            "MCP_PUBLIC_BASE_URL" to publicBaseUrl,
            "MCP_OAUTH_CLIENT_ID" to clientId,
            "MCP_OAUTH_CLIENT_SECRET" to (clientSecret?.let { "******" }),
            "MCP_AUTH_TOKEN" to (staticAuthToken?.let { "******" }),
            "requireAuth" to requireAuth,
            "isAuthEnabled" to isAuthEnabled,
        )

    /**
     * Logs the masked active configuration to the provided or default logger.
     */
    fun auditLog(logger: Logger = LoggerFactory.getLogger(McpServerConfig::class.java)) {
        Companion.auditLog(this, logger)
    }

    companion object {
        const val DEFAULT_PORT = 8081
        const val DEFAULT_DB_PATH = "./data/urls.db"
        const val DEFAULT_PUBLIC_BASE_URL = "https://woodendoor.website"
        const val DEFAULT_CLIENT_ID = "luna-mcp-client"

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
         * Masks sensitive configuration values in a map with '******'.
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
         * Emits structured audit log displaying active configuration with sensitive values masked.
         */
        fun auditLog(
            config: McpServerConfig,
            logger: Logger = LoggerFactory.getLogger(McpServerConfig::class.java),
        ) {
            val masked = config.toMaskedMap()
            logger.info("MCP server configuration: {}", masked)
        }

        /**
         * Emits structured audit log displaying raw configuration map with sensitive values masked.
         */
        fun auditLog(
            config: Map<String, Any?>,
            logger: Logger = LoggerFactory.getLogger(McpServerConfig::class.java),
        ) {
            val masked = maskConfig(config)
            logger.info("MCP server configuration: {}", masked)
        }

        /**
         * Parses a .env format string or sequence into a key-value map.
         */
        fun parseDotenv(lines: Sequence<String>): Map<String, String> {
            val result = mutableMapOf<String, String>()
            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue
                }
                val line =
                    if (trimmed.startsWith("export ")) {
                        trimmed.removePrefix("export ").trimStart()
                    } else {
                        trimmed
                    }
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    var value = parts[1].trim()
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))
                    ) {
                        value = value.substring(1, value.length - 1)
                    }
                    if (key.isNotEmpty()) {
                        result[key] = value
                    }
                }
            }
            return result
        }

        fun parseDotenv(content: String): Map<String, String> = parseDotenv(content.lineSequence())

        fun parseDotenv(file: File): Map<String, String> = file.useLines { parseDotenv(it) }

        /**
         * Automatically detects the project root .env file when running outside Docker.
         * Checks the current working directory first, then the parent directory.
         */
        fun findDefaultEnvFile(baseDir: File = File(".")): File? {
            val direct = File(baseDir, ".env")
            if (direct.exists() && direct.isFile) return direct
            val parent = File(baseDir, "../.env")
            if (parent.exists() && parent.isFile) return parent
            return null
        }

        private fun parseBoolean(value: Any?): Boolean =
            when (value) {
                null -> false
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> value.trim().lowercase() in setOf("true", "1", "yes", "enabled")
                else -> false
            }

        /**
         * Builds [McpServerConfig] from a key-value map with canonical defaults and fail-fast validation.
         */
        fun fromMap(
            config: Map<String, Any?>,
            authExpected: Boolean? = null,
        ): McpServerConfig {
            val rawPort = config["MCP_PORT"] ?: config["PORT"] ?: config["port"] ?: config["mcp.port"]
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
                                ?: throw McpConfigValidationException(
                                    "Invalid configuration: MCP_PORT must be an integer, but got: '$rawPort'",
                                )
                        }
                    }

                    else -> {
                        rawPort.toString().trim().toIntOrNull()
                            ?: throw McpConfigValidationException(
                                "Invalid configuration: MCP_PORT must be an integer, but got: '$rawPort'",
                            )
                    }
                }

            if (port !in 1..65535) {
                throw McpConfigValidationException(
                    "Invalid configuration: PORT must be between 1 and 65535, but got: $port",
                )
            }

            val rawDbPath =
                (config["MCP_DB_PATH"] ?: config["dbPath"] ?: config["mcp.dbPath"])?.toString()
            val dbPath = if (rawDbPath.isNullOrBlank()) DEFAULT_DB_PATH else rawDbPath

            val rawBaseUrl =
                (config["MCP_PUBLIC_BASE_URL"] ?: config["publicBaseUrl"] ?: config["mcp.publicBaseUrl"])?.toString()
            val publicBaseUrl = if (rawBaseUrl.isNullOrBlank()) DEFAULT_PUBLIC_BASE_URL else rawBaseUrl

            val rawClientId =
                (config["MCP_OAUTH_CLIENT_ID"] ?: config["clientId"] ?: config["mcp.clientId"] ?: config["MCP_CLIENT_ID"])?.toString()
            val clientId = if (rawClientId.isNullOrBlank()) DEFAULT_CLIENT_ID else rawClientId

            val rawClientSecret =
                (config["MCP_OAUTH_CLIENT_SECRET"] ?: config["clientSecret"] ?: config["mcp.clientSecret"] ?: config["MCP_CLIENT_SECRET"])?.toString()
            val clientSecret = rawClientSecret?.takeIf { it.isNotBlank() }

            val rawToken =
                (config["MCP_AUTH_TOKEN"] ?: config["staticAuthToken"] ?: config["mcp.staticAuthToken"])?.toString()
            val staticAuthToken = rawToken?.takeIf { it.isNotBlank() }

            val rawRequireAuth = config["MCP_REQUIRE_AUTH"] ?: config["requireAuth"] ?: config["mcp.requireAuth"]
            val requireAuth = authExpected ?: parseBoolean(rawRequireAuth)

            if (requireAuth && clientSecret == null && staticAuthToken == null) {
                throw McpConfigValidationException(
                    "Invalid configuration: Authentication is required, but both clientSecret and staticAuthToken are blank",
                )
            }

            return McpServerConfig(
                port = port,
                dbPath = dbPath,
                publicBaseUrl = publicBaseUrl,
                clientId = clientId,
                clientSecret = clientSecret,
                staticAuthToken = staticAuthToken,
                requireAuth = requireAuth,
            )
        }

        /**
         * Loads configuration raw key-value map with layered precedence:
         * .env file < System.getenv() < customConfig overrides.
         */
        fun loadRawConfig(
            envFile: File? = findDefaultEnvFile(),
            systemEnv: Map<String, String> = System.getenv(),
            customConfig: Map<String, Any?>? = null,
        ): Map<String, Any?> {
            val config = mutableMapOf<String, Any?>()
            if (envFile != null && envFile.exists() && envFile.isFile) {
                config.putAll(parseDotenv(envFile))
            }
            config.putAll(systemEnv)
            if (customConfig != null) {
                config.putAll(customConfig)
            }
            return config
        }

        /**
         * In-memory source loader for deterministic unit testing of precedence layers.
         */
        fun loadFromSources(
            dotenvContent: String? = null,
            systemEnv: Map<String, String> = emptyMap(),
            customConfig: Map<String, Any?>? = null,
            authExpected: Boolean? = null,
        ): McpServerConfig {
            val config = mutableMapOf<String, Any?>()
            if (dotenvContent != null) {
                config.putAll(parseDotenv(dotenvContent))
            }
            config.putAll(systemEnv)
            if (customConfig != null) {
                config.putAll(customConfig)
            }
            return fromMap(config, authExpected = authExpected)
        }

        /**
         * Loads and validates the standalone MCP server configuration with fail-fast validation.
         */
        fun load(
            envFile: File? = findDefaultEnvFile(),
            systemEnv: Map<String, String> = System.getenv(),
            customConfig: Map<String, Any?>? = null,
            authExpected: Boolean? = null,
        ): McpServerConfig {
            val raw = loadRawConfig(envFile, systemEnv, customConfig)
            auditDrift(raw, authExpected = authExpected)
            return fromMap(raw, authExpected = authExpected)
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
         * and unknown or deprecated variables for MCP server.
         */
        fun auditDrift(
            config: Map<String, Any?>,
            envExampleContent: String? = loadEnvExample(),
            requiredKeys: Set<String>? = null,
            authExpected: Boolean? = null,
            logger: Logger = LoggerFactory.getLogger(McpServerConfig::class.java),
        ): DriftAuditResult {
            val schemaList =
                if (envExampleContent != null) {
                    val parsed = parseEnvExampleSchema(envExampleContent)
                    if (parsed.isNotEmpty()) parsed else loadEnvExampleSchema()
                } else {
                    loadEnvExampleSchema()
                }

            val schemaKeys = schemaList.map { it.name }.toSet()
            val mcpSchema = schemaList.filter { it.section == 5 || it.name.startsWith("MCP_") }

            // 1. Missing required variables
            val missingRequired = mutableListOf<String>()
            if (requiredKeys != null) {
                for (req in requiredKeys) {
                    val value = resolveVariableValue(req, config)
                    if (value == null) {
                        missingRequired.add(req)
                    }
                }
            } else {
                for (item in mcpSchema.filter { it.isRequired }) {
                    val value = resolveVariableValue(item.name, config)
                    if (value == null) {
                        missingRequired.add(item.name)
                    }
                }
            }

            val rawRequireAuth = config["MCP_REQUIRE_AUTH"] ?: config["requireAuth"] ?: config["mcp.requireAuth"]
            val isAuth = authExpected ?: parseBoolean(rawRequireAuth)
            if (isAuth) {
                val secret =
                    (
                        config["MCP_OAUTH_CLIENT_SECRET"]
                            ?: config["clientSecret"]
                            ?: config["mcp.clientSecret"]
                            ?: config["MCP_CLIENT_SECRET"]
                    )?.toString()
                val token =
                    (
                        config["MCP_AUTH_TOKEN"]
                            ?: config["staticAuthToken"]
                            ?: config["mcp.staticAuthToken"]
                    )?.toString()
                if (secret.isNullOrBlank() && token.isNullOrBlank()) {
                    missingRequired.add("MCP_OAUTH_CLIENT_SECRET or MCP_AUTH_TOKEN")
                }
            }

            if (missingRequired.isNotEmpty()) {
                val errMsg = "Missing required configuration: ${missingRequired.joinToString(", ")} must not be blank"
                logger.error(
                    "Layer: {}, Component: {}, Operation: {}, Error: {}",
                    kv("layer", "CONFIG"),
                    kv("component", "McpServer"),
                    kv("operation", "startupValidation"),
                    kv("error_message", errMsg),
                    kv("data", mapOf("missingRequired" to missingRequired)),
                    kv("status", "FAILURE"),
                )
                throw McpConfigValidationException(errMsg)
            }

            // 2. Missing optional variables
            val missingOptional = mutableMapOf<String, Any?>()
            val optionalItems = if (mcpSchema.isNotEmpty()) mcpSchema.filter { !it.isRequired } else defaultFallbackSchema()
            for (item in optionalItems) {
                val value = resolveVariableValue(item.name, config)
                if (value == null) {
                    val defaultVal =
                        item.defaultValue ?: when (item.name) {
                            "MCP_PORT" -> DEFAULT_PORT
                            "MCP_DB_PATH" -> DEFAULT_DB_PATH
                            "MCP_PUBLIC_BASE_URL" -> DEFAULT_PUBLIC_BASE_URL
                            "MCP_OAUTH_CLIENT_ID" -> DEFAULT_CLIENT_ID
                            "MCP_REQUIRE_AUTH" -> false
                            else -> null
                        }
                    if (defaultVal != null) {
                        missingOptional[item.name] = defaultVal
                        val maskedDefault = maskValue(item.name, defaultVal)
                        logger.info(
                            "Layer: {}, Component: {}, Operation: {}, Key: {}, DefaultValue: {}",
                            kv("layer", "CONFIG"),
                            kv("component", "McpServer"),
                            kv("operation", "optionalDefaultApplied"),
                            kv("key", item.name),
                            kv("defaultValue", maskedDefault),
                            kv("data", mapOf("component" to "McpServer", "key" to item.name, "defaultValue" to maskedDefault)),
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
                logger.warn(
                    "{}",
                    warnMessage,
                    kv("layer", "CONFIG"),
                    kv("component", "McpServer"),
                    kv("operation", "startupDriftAudit"),
                    kv("message", warnMessage),
                    kv("level", "WARN"),
                    kv("data", mapOf("unknownKeys" to unknownKeys)),
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
                ?: McpServerConfig::class.java.getResourceAsStream("/.env.example")
                ?: findDefaultEnvFile()
                    ?.parentFile
                    ?.let { File(it, ".env.example") }
                    ?.takeIf { it.exists() && it.isFile }
                    ?.inputStream()
                ?: File(".env.example").takeIf { it.exists() && it.isFile }?.inputStream()

        /**
         * Reads the canonical .env.example specification content as a String.
         */
        fun loadEnvExample(): String? = getEnvExampleStream()?.bufferedReader()?.use { it.readText() }
    }
}
