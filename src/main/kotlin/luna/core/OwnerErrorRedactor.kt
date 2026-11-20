package luna.core

import luna.config.EnvReader
import luna.config.SecretMasker

/**
 * Redacts sensitive configuration values, credentials, headers, and query secrets
 * from Owner Error Notification messages, stack traces, and context fields using
 * `:libs:config` [SecretMasker].
 */
class OwnerErrorRedactor(
    explicitSecrets: Collection<String> = emptyList(),
) {
    private val normalizedSecrets: List<String> =
        explicitSecrets
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_SECRET_LENGTH && it != MASK }
            .distinct()
            .sortedByDescending { it.length }
            .toList()

    fun redact(input: String?): String? {
        if (input == null) return null
        var result: String = input
        result = QUERY_KEY_REGEX.replace(result) { "${it.groupValues[1]}$MASK" }
        result = HEADER_GOOG_KEY_REGEX.replace(result) { "${it.groupValues[1]}$MASK" }
        result = BEARER_AUTH_REGEX.replace(result) { "${it.groupValues[1]}$MASK" }
        result = INLINE_SECRET_KV_REGEX.replace(result) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}$MASK${it.groupValues[3]}" }
        for (secret in normalizedSecrets) {
            if (result.contains(secret)) {
                result = result.replace(secret, MASK)
            }
        }
        return result
    }

    fun redactEvent(event: OwnerErrorEvent): OwnerErrorEvent =
        event.copy(
            message = redact(event.message) ?: "",
            stackTrace = redact(event.stackTrace),
            contextFields =
                event.contextFields.mapValues { (key, value) ->
                    if (SecretMasker.isSensitiveKey(key)) MASK else (redact(value) ?: "")
                },
        )

    companion object {
        const val MASK = "******"
        private const val MIN_SECRET_LENGTH = 3

        private val QUERY_KEY_REGEX =
            Regex("""([?&](?:api_?key|key|token|secret)=)([^&#\s"']+)""", RegexOption.IGNORE_CASE)

        private val HEADER_GOOG_KEY_REGEX =
            Regex("""(x-goog-api-key\s*[:=]\s*["']?)([^"'\s,;}\]]+)""", RegexOption.IGNORE_CASE)

        private val BEARER_AUTH_REGEX =
            Regex("""(authorization\s*[:=]\s*["']?Bearer\s+)([^"'\s,;}\]]+)""", RegexOption.IGNORE_CASE)

        private val INLINE_SECRET_KV_REGEX =
            Regex(
                """\b([a-z0-9_.]*(?:token|secret|password|api_?key))\s*([=:])\s*(["']?)([^\s"',;}\]]+)\3""",
                RegexOption.IGNORE_CASE,
            )

        fun from(env: EnvReader): OwnerErrorRedactor = fromConfig(env.toMap())

        fun fromConfig(config: Map<String, Any?>): OwnerErrorRedactor {
            val secrets = mutableListOf<String>()
            collectSecretsFromMap(config, secrets)
            return OwnerErrorRedactor(secrets)
        }

        private fun collectSecretsFromMap(
            map: Map<*, *>,
            target: MutableList<String>,
        ) {
            for ((rawKey, value) in map) {
                val key = rawKey?.toString() ?: continue
                when (value) {
                    is Map<*, *> -> {
                        collectSecretsFromMap(value, target)
                    }

                    is String -> {
                        if (SecretMasker.isSensitiveKey(key) && value.isNotBlank()) {
                            target.add(value)
                        }
                    }
                }
            }
        }
    }
}
