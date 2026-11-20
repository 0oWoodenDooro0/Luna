package luna.core

import luna.config.ConfigFinder
import luna.config.ConfigValidationException
import luna.config.DotenvParser
import luna.config.EnvReader
import luna.config.EnvSchemaAuditor
import luna.config.SecretMasker
import java.io.File
import java.io.InputStream

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
            failValidation("Missing required configuration: DISCORD_CLIENT_ID must not be blank")
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

        fun from(env: EnvReader): LunaCoreConfig {
            try {
                val token = env.requiredString("DISCORD_TOKEN")
                val clientId = env.requiredString("DISCORD_CLIENT_ID")
                val clientSecret = env.requiredString("DISCORD_CLIENT_SECRET")
                val port = env.port("PORT", DEFAULT_PORT)
                val baseUrl = env.string("BASE_URL", DEFAULT_BASE_URL)
                val redirectUri = env.string("DISCORD_REDIRECT_URI") ?: defaultRedirectUri(baseUrl)

                return LunaCoreConfig(
                    botToken = token,
                    port = port,
                    baseUrl = baseUrl,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    redirectUri = redirectUri,
                )
            } catch (e: ConfigValidationException) {
                failValidation(e.message ?: "Invalid Luna core configuration")
            }
        }

        fun fromMap(config: Map<String, Any?>): LunaCoreConfig = from(EnvReader.of(ConfigLoader.toStringMap(config)))
    }
}

/**
 * Configuration loader implementing two-layer precedence:
 * System.getenv() > .env file key-values.
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

    internal fun toStringMap(config: Map<String, Any?>): Map<String, String> =
        config.entries.mapNotNull { (key, value) -> value?.toString()?.let { key to it } }.toMap()

    /**
     * Loads configuration across two layers with strict precedence:
     * System.getenv() > .env file key-values (plus optional overrides).
     */
    fun loadConfig(
        customConfig: Map<String, Any?>? = null,
        envFile: File? = findDefaultEnvFile() ?: File(".env"),
        systemEnv: Map<String, String> = System.getenv(),
    ): Map<String, Any?> =
        EnvReader
            .load(
                envFile = envFile,
                systemEnv = systemEnv,
                overrides = customConfig?.let(::toStringMap),
                auditDrift = false,
            ).toMap()

    /**
     * In-memory source loader for deterministic unit testing of precedence layers.
     */
    fun loadConfigFromSources(
        dotenvContent: String? = null,
        systemEnv: Map<String, String> = emptyMap(),
        customConfig: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val config = mutableMapOf<String, Any?>()
        if (dotenvContent != null) {
            config.putAll(parseDotenv(dotenvContent))
        }
        config.putAll(systemEnv)
        if (customConfig != null) {
            config.putAll(customConfig)
        }
        return config
    }

    /**
     * Loads and validates the core configuration, throwing [ConfigValidationException] if invalid.
     */
    fun loadCoreConfig(customConfig: Map<String, Any?>? = null): LunaCoreConfig {
        val env = EnvReader.load(overrides = customConfig?.let(::toStringMap), auditDrift = false)
        auditDrift(env.toMap())
        return LunaCoreConfig.from(env)
    }

    val STANDARD_SYSTEM_KEYS: Set<String> get() = EnvSchemaAuditor.STANDARD_SYSTEM_KEYS

    fun parseDefaultValue(raw: String): String? = EnvSchemaAuditor.parseDefaultValue(raw)

    fun parseEnvExampleSchema(content: String): List<EnvVarSchema> = EnvSchemaAuditor.parseEnvExampleSchema(content)

    fun defaultFallbackSchema(): List<EnvVarSchema> = EnvSchemaAuditor.defaultFallbackSchema()

    fun loadEnvExampleSchema(): List<EnvVarSchema> = EnvSchemaAuditor.loadDefaultSchema()

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

        val auditResult =
            EnvSchemaAuditor.auditDrift(
                config = config,
                schema = hostSchema,
                allSchemaKeys = schemaKeys,
                requiredKeys = requiredKeys,
                fallbackDefaults = fallbackDefaults,
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
