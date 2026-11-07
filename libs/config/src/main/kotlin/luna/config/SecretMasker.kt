package luna.config

/**
 * Single authoritative implementation for sensitive configuration key detection
 * and secret masking across Luna Host and Decoupled MCP Server.
 */
object SecretMasker {
    val SENSITIVE_EXPLICIT_KEYS: Set<String> =
        setOf(
            "DISCORD_TOKEN",
            "DISCORD_CLIENT_SECRET",
            "GEMINI_API_KEY",
            "YOUTUBE_API_KEY",
        )

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

    fun maskConfig(config: Map<String, Any?>): Map<String, Any?> =
        config.mapValues { (key, value) ->
            maskValue(key, value)
        }
}
