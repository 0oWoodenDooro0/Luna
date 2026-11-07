package luna.mcp.server

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

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
            return fromMap(raw, authExpected = authExpected)
        }
    }
}
