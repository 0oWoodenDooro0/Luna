package luna.core

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
 * Strongly-typed data model encapsulating validated core Luna bot settings.
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
        return LunaCoreConfig.fromMap(config)
    }
}
