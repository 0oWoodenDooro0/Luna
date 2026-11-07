package luna.mcp.server

import luna.config.ConfigFinder
import luna.config.DotenvParser
import luna.config.EnvSchemaAuditor
import luna.config.SecretMasker
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
typealias EnvVarSchema = luna.config.EnvVarSchema
typealias DriftAuditResult = luna.config.DriftAuditResult

/**
 * McpServerConfig defines typed configuration for the standalone MCP microservice.
 * Underlying parsing, secret masking, and drift audit rules are delegated to the
 * shared [luna.config] deep module (:libs:config).
 * See also: [configuration.md](file:///home/user/IdeaProjects/Luna/docs/architecture/configuration.md).
 */
data class McpServerConfig(
    val port: Int = DEFAULT_PORT,
    val dbPath: String = DEFAULT_DB_PATH,
    val publicBaseUrl: String = DEFAULT_PUBLIC_BASE_URL,
) {
    val normalizedBaseUrl: String = publicBaseUrl.trimEnd('/')

    init {
        if (port !in 1..65535) {
            throw McpConfigValidationException("Invalid configuration: PORT must be between 1 and 65535, but got: $port")
        }
    }

    /**
     * Converts this MCP server configuration to a compatible [McpOAuthConfig] instance.
     */
    fun toOAuthConfig(): McpOAuthConfig =
        McpOAuthConfig(
            publicBaseUrl = normalizedBaseUrl,
        )

    /**
     * Returns a representation of active configuration with sensitive values masked.
     */
    fun toMaskedMap(): Map<String, Any?> =
        mapOf(
            "MCP_PORT" to port,
            "MCP_DB_PATH" to dbPath,
            "MCP_PUBLIC_BASE_URL" to publicBaseUrl,
        )

    /**
     * Logs the masked active configuration to the provided or default logger.
     */
    fun auditLog(logger: Logger = LoggerFactory.getLogger(McpServerConfig::class.java)) {
        Companion.auditLog(this, logger)
    }

    companion object {
        private val defaultLogger: Logger = LoggerFactory.getLogger(McpServerConfig::class.java)

        const val DEFAULT_PORT = 8081
        const val DEFAULT_DB_PATH = "./data/urls.db"
        const val DEFAULT_PUBLIC_BASE_URL = "https://woodendoor.website"

        val SENSITIVE_EXPLICIT_KEYS: Set<String> get() = SecretMasker.SENSITIVE_EXPLICIT_KEYS

        /**
         * Checks if the configuration key contains sensitive information that must be masked.
         */
        fun isSensitiveKey(key: String): Boolean = SecretMasker.isSensitiveKey(key)

        /**
         * Masks sensitive configuration values in a map with '******'.
         */
        fun maskConfig(config: Map<String, Any?>): Map<String, Any?> = SecretMasker.maskConfig(config)

        fun maskValue(
            key: String,
            value: Any?,
        ): Any? = SecretMasker.maskValue(key, value)

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
        fun parseDotenv(lines: Sequence<String>): Map<String, String> = DotenvParser.parse(lines)

        fun parseDotenv(content: String): Map<String, String> = DotenvParser.parse(content)

        fun parseDotenv(file: File): Map<String, String> = DotenvParser.parse(file)

        /**
         * Automatically detects the project root .env file when running outside Docker.
         */
        fun findDefaultEnvFile(baseDir: File = File(".")): File? = ConfigFinder.findDefaultEnvFile(baseDir)

        /**
         * Builds [McpServerConfig] from a key-value map with canonical defaults and fail-fast validation.
         */
        fun fromMap(config: Map<String, Any?>): McpServerConfig {
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

            return McpServerConfig(
                port = port,
                dbPath = dbPath,
                publicBaseUrl = publicBaseUrl,
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
        ): McpServerConfig {
            val config = mutableMapOf<String, Any?>()
            if (dotenvContent != null) {
                config.putAll(parseDotenv(dotenvContent))
            }
            config.putAll(systemEnv)
            if (customConfig != null) {
                config.putAll(customConfig)
            }
            return fromMap(config)
        }

        /**
         * Loads and validates the standalone MCP server configuration with fail-fast validation.
         */
        fun load(
            envFile: File? = findDefaultEnvFile(),
            systemEnv: Map<String, String> = System.getenv(),
            customConfig: Map<String, Any?>? = null,
        ): McpServerConfig {
            val raw = loadRawConfig(envFile, systemEnv, customConfig)
            auditDrift(raw)
            return fromMap(raw)
        }

        val STANDARD_SYSTEM_KEYS: Set<String> get() = EnvSchemaAuditor.STANDARD_SYSTEM_KEYS
        val CORE_KNOWN_ALIASES: Map<String, String> get() = EnvSchemaAuditor.CORE_KNOWN_ALIASES

        fun parseDefaultValue(raw: String): String? = EnvSchemaAuditor.parseDefaultValue(raw)

        fun parseEnvExampleSchema(content: String): List<EnvVarSchema> = EnvSchemaAuditor.parseEnvExampleSchema(content)

        fun loadEnvExampleSchema(): List<EnvVarSchema> = EnvSchemaAuditor.loadDefaultSchema()

        fun loadEnvExampleStream(): InputStream? = EnvSchemaAuditor.loadDefaultEnvExampleStream()

        fun loadEnvExample(): String? = EnvSchemaAuditor.loadDefaultEnvExample()

        fun isRecognizedKey(
            key: String,
            schemaKeys: Set<String>,
        ): Boolean = EnvSchemaAuditor.isRecognizedKey(key, schemaKeys)

        fun resolveVariableValue(
            key: String,
            config: Map<String, Any?>,
        ): Any? = EnvSchemaAuditor.resolveVariableValue(key, config)

        /**
         * Performs startup drift audit checking missing required, missing optional with defaults,
         * and unknown or deprecated variables for MCP server.
         */
        fun auditDrift(
            config: Map<String, Any?>,
            envExampleContent: String? = loadEnvExample(),
            requiredKeys: Set<String>? = null,
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

            val fallbackDefaults =
                mapOf(
                    "MCP_PORT" to DEFAULT_PORT,
                    "MCP_DB_PATH" to DEFAULT_DB_PATH,
                    "MCP_PUBLIC_BASE_URL" to DEFAULT_PUBLIC_BASE_URL,
                )

            val auditResult =
                EnvSchemaAuditor.auditDrift(
                    config = config,
                    schema = mcpSchema,
                    allSchemaKeys = schemaKeys,
                    requiredKeys = requiredKeys,
                    fallbackDefaults = fallbackDefaults,
                )

            if (auditResult.missingRequired.isNotEmpty()) {
                val errMsg = "Missing required configuration: ${auditResult.missingRequired.joinToString(", ")} must not be blank"
                logger.error(
                    "Layer: {}, Component: {}, Operation: {}, Error: {}",
                    kv("layer", "CONFIG"),
                    kv("component", "McpServer"),
                    kv("operation", "startupValidation"),
                    kv("error_message", errMsg),
                    kv("data", mapOf("missingRequired" to auditResult.missingRequired)),
                    kv("status", "FAILURE"),
                )
                throw McpConfigValidationException(errMsg)
            }

            for ((key, defaultVal) in auditResult.missingOptional) {
                val maskedDefault = maskValue(key, defaultVal)
                logger.info(
                    "Layer: {}, Component: {}, Operation: {}, Key: {}, DefaultValue: {}",
                    kv("layer", "CONFIG"),
                    kv("component", "McpServer"),
                    kv("operation", "optionalDefaultApplied"),
                    kv("key", key),
                    kv("defaultValue", maskedDefault),
                    kv("data", mapOf("component" to "McpServer", "key" to key, "defaultValue" to maskedDefault)),
                )
            }

            if (auditResult.unknownKeys.isNotEmpty()) {
                val warnMessage = "[CONFIG_DRIFT] Unknown or deprecated environment keys: ${auditResult.unknownKeys}"
                logger.warn(
                    "{}",
                    warnMessage,
                    kv("layer", "CONFIG"),
                    kv("component", "McpServer"),
                    kv("operation", "startupDriftAudit"),
                    kv("message", warnMessage),
                    kv("level", "WARN"),
                    kv("data", mapOf("unknownKeys" to auditResult.unknownKeys)),
                )
            }

            return auditResult
        }
    }
}
