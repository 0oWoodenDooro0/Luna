package luna.core

import luna.config.ConfigFinder
import luna.config.DotenvParser
import luna.config.EnvSchemaAuditor
import luna.config.PlaceholderResolver
import luna.config.SecretMasker
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

typealias EnvVarSchema = luna.config.EnvVarSchema
typealias DriftAuditResult = luna.config.DriftAuditResult

/**
 * LunaCoreConfig defines typed configuration for the Luna bot host.
 * Underlying parsing, secret masking, and drift audit rules are delegated to the
 * shared [luna.config] deep module (:libs:config).
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
 * Parsing, masking, and schema audit are delegated to [luna.config].
 */
object ConfigLoader {
    val SENSITIVE_EXPLICIT_KEYS: Set<String> get() = SecretMasker.SENSITIVE_EXPLICIT_KEYS

    /**
     * Checks if the configuration key contains sensitive information that must be masked.
     */
    fun isSensitiveKey(key: String): Boolean = SecretMasker.isSensitiveKey(key)

    /**
     * Masks sensitive configuration values with '******'.
     */
    fun maskConfig(config: Map<String, Any?>): Map<String, Any?> = SecretMasker.maskConfig(config)

    fun maskValue(
        key: String,
        value: Any?,
    ): Any? = SecretMasker.maskValue(key, value)

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
    fun parseDotenv(lines: Sequence<String>): Map<String, String> = DotenvParser.parse(lines)

    fun parseDotenv(content: String): Map<String, String> = DotenvParser.parse(content)

    fun parseDotenv(file: File): Map<String, String> = DotenvParser.parse(file)

    fun findDefaultEnvFile(baseDir: File = File(".")): File? = ConfigFinder.findDefaultEnvFile(baseDir)

    /**
     * Resolves `${VAR:default}` or `${?VAR}` placeholders in string values against the current config and system environment.
     */
    fun resolvePlaceholders(
        raw: String,
        config: Map<String, Any?>,
    ): String = PlaceholderResolver.resolve(raw, config)

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
        envFile: File? = findDefaultEnvFile() ?: File(".env"),
        systemEnv: Map<String, String> = System.getenv(),
        yamlStreamProvider: (() -> InputStream?)? = null,
    ): Map<String, Any?> {
        val config = mutableMapOf<String, Any?>()

        // 1. Ingest .env if present
        if (envFile != null && envFile.exists() && envFile.isFile) {
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

    val STANDARD_SYSTEM_KEYS: Set<String> get() = EnvSchemaAuditor.STANDARD_SYSTEM_KEYS
    val CORE_KNOWN_ALIASES: Map<String, String> get() = EnvSchemaAuditor.CORE_KNOWN_ALIASES

    fun parseDefaultValue(raw: String): String? = EnvSchemaAuditor.parseDefaultValue(raw)

    fun parseEnvExampleSchema(content: String): List<EnvVarSchema> = EnvSchemaAuditor.parseEnvExampleSchema(content)

    fun defaultFallbackSchema(): List<EnvVarSchema> = EnvSchemaAuditor.defaultFallbackSchema()

    fun loadEnvExampleSchema(): List<EnvVarSchema> = EnvSchemaAuditor.loadDefaultSchema()

    fun isRecognizedKey(
        key: String,
        schemaKeys: Set<String>,
    ): Boolean = EnvSchemaAuditor.isRecognizedKey(key, schemaKeys)

    fun isRecognizedKey(
        key: String,
        schemaKeys: Set<String>,
        candidateAliases: Map<String, List<String>>,
    ): Boolean = EnvSchemaAuditor.isRecognizedKey(key, schemaKeys, candidateAliases)

    fun resolveVariableValue(
        key: String,
        config: Map<String, Any?>,
    ): Any? = EnvSchemaAuditor.resolveVariableValue(key, config)

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

        val fallbackDefaults =
            mapOf(
                "PORT" to 8080,
                "BASE_URL" to LunaCoreConfig.DEFAULT_BASE_URL,
                "DISCORD_REDIRECT_URI" to LunaCoreConfig.defaultRedirectUri(LunaCoreConfig.DEFAULT_BASE_URL),
            )

        val candidateAliases =
            mapOf(
                "DISCORD_APP_ID" to listOf("DISCORD_CLIENT_ID"),
                "clientId" to listOf("DISCORD_CLIENT_ID"),
                "luna.clientId" to listOf("DISCORD_CLIENT_ID"),
                "botToken" to listOf("DISCORD_TOKEN"),
                "luna.botToken" to listOf("DISCORD_TOKEN"),
                "clientSecret" to listOf("DISCORD_CLIENT_SECRET"),
                "luna.clientSecret" to listOf("DISCORD_CLIENT_SECRET"),
                "port" to listOf("PORT"),
                "luna.port" to listOf("PORT"),
                "MCP_PORT" to listOf("PORT"),
                "mcp.port" to listOf("PORT", "MCP_PORT"),
                "baseUrl" to listOf("BASE_URL"),
                "luna.baseUrl" to listOf("BASE_URL"),
                "curtly.baseUrl" to listOf("BASE_URL"),
                "curtly.base_url" to listOf("BASE_URL"),
                "CURTLY_BASE_URL" to listOf("BASE_URL"),
                "PLUGINS_CURTLY_BASE_URL" to listOf("BASE_URL"),
                "redirectUri" to listOf("DISCORD_REDIRECT_URI"),
                "REDIRECT_URI" to listOf("DISCORD_REDIRECT_URI"),
                "luna.redirectUri" to listOf("DISCORD_REDIRECT_URI"),
                "ai.trace.enabled" to listOf("AI_TRACE_ENABLED"),
                "ai.trace.dir" to listOf("AI_TRACE_DIR"),
                "ai.trace.maxDays" to listOf("AI_TRACE_MAX_DAYS"),
                "ai.trace.max_days" to listOf("AI_TRACE_MAX_DAYS"),
                "ai.trace.maxTotalMb" to listOf("AI_TRACE_MAX_TOTAL_MB"),
                "ai.trace.max_total_mb" to listOf("AI_TRACE_MAX_TOTAL_MB"),
            )

        val auditResult =
            EnvSchemaAuditor.auditDrift(
                config = config,
                schema = hostSchema,
                allSchemaKeys = schemaKeys,
                requiredKeys = requiredKeys,
                fallbackDefaults = fallbackDefaults,
                candidateAliases = candidateAliases,
            )

        if (auditResult.missingRequired.isNotEmpty()) {
            val errMsg = "Missing required configuration: ${auditResult.missingRequired.joinToString(", ")} must not be blank"
            failValidation(errMsg)
        }

        for ((key, defaultVal) in auditResult.missingOptional) {
            val maskedDefault = maskValue(key, defaultVal)
            JsonLogger.log(
                layer = "CONFIG",
                component = "LunaHost",
                operation = "startupAudit",
                data =
                    mapOf(
                        "component" to "LunaHost",
                        "key" to key,
                        "defaultValue" to maskedDefault,
                    ),
                status = "APPLIED_DEFAULT",
            )
        }

        if (auditResult.unknownKeys.isNotEmpty()) {
            val warnMessage = "[CONFIG_DRIFT] Unknown or deprecated environment keys: ${auditResult.unknownKeys}"
            JsonLogger.warn(
                layer = "CONFIG",
                component = "LunaHost",
                operation = "startupDriftAudit",
                data = mapOf("unknownKeys" to auditResult.unknownKeys),
                message = warnMessage,
            )
        }

        return auditResult
    }

    /**
     * Loads the canonical .env.example specification as an InputStream from classpath resources or filesystem.
     */
    fun getEnvExampleStream(): InputStream? = EnvSchemaAuditor.loadDefaultEnvExampleStream()

    /**
     * Reads the canonical .env.example specification content as a String.
     */
    fun loadEnvExample(): String? = EnvSchemaAuditor.loadDefaultEnvExample()
}
