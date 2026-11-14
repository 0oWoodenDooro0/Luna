package luna.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import luna.config.SecretMasker

/**
 * Scrubs API keys, sensitive headers, and configured secret values from AI Trace payloads
 * before serialization.
 */
class AiTraceRedactor(
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

    fun withExtraSecrets(extraSecrets: Collection<String>): AiTraceRedactor {
        if (extraSecrets.isEmpty()) return this
        val filtered = extraSecrets.map { it.trim() }.filter { it.length >= MIN_SECRET_LENGTH && it != MASK }
        if (filtered.isEmpty() || normalizedSecrets.containsAll(filtered)) return this
        return AiTraceRedactor(normalizedSecrets + filtered)
    }

    fun redactString(input: String?): String? {
        if (input == null) return null
        var result: String = input
        result = QUERY_KEY_REGEX.replace(result) { "${it.groupValues[1]}$MASK" }
        result = HEADER_GOOG_KEY_REGEX.replace(result) { "${it.groupValues[1]}$MASK" }
        result = BEARER_AUTH_REGEX.replace(result) { "${it.groupValues[1]}$MASK" }
        for (secret in normalizedSecrets) {
            if (result.contains(secret)) {
                result = result.replace(secret, MASK)
            }
        }
        return result
    }

    fun redactJsonElement(element: JsonElement?): JsonElement? =
        when (element) {
            null -> {
                null
            }

            is JsonNull -> {
                element
            }

            is JsonPrimitive -> {
                if (element.isString) {
                    JsonPrimitive(redactString(element.content))
                } else {
                    element
                }
            }

            is JsonArray -> {
                JsonArray(element.map { redactJsonElement(it) ?: JsonNull })
            }

            is JsonObject -> {
                JsonObject(
                    element.entries.associate { (key, value) ->
                        val sanitizedValue =
                            when {
                                (key == "inlineData" || key == "inline_data") && value is JsonObject -> {
                                    JsonObject(
                                        value.entries.associate { (innerKey, innerVal) ->
                                            if (innerKey == "data" && innerVal !is JsonNull) {
                                                innerKey to JsonPrimitive(REDACTED_BINARY)
                                            } else {
                                                innerKey to (redactJsonElement(innerVal) ?: JsonNull)
                                            }
                                        },
                                    )
                                }

                                (SecretMasker.isSensitiveKey(key) || key.equals("x-goog-api-key", ignoreCase = true)) &&
                                    value !is JsonNull -> {
                                    JsonPrimitive(MASK)
                                }

                                else -> {
                                    redactJsonElement(value) ?: JsonNull
                                }
                            }
                        key to sanitizedValue
                    },
                )
            }
        }

    fun redactAttempt(attempt: AiAttemptRecord): AiAttemptRecord =
        attempt.copy(
            endpoint = redactString(attempt.endpoint),
            request =
                attempt.request?.let { req ->
                    req.copy(payload = redactJsonElement(req.payload))
                },
            responseRaw = redactString(attempt.responseRaw),
            errorMessage = redactString(attempt.errorMessage),
        )

    fun redactTrace(trace: AiTraceRecord): AiTraceRecord =
        trace.copy(
            finalOutput = redactString(trace.finalOutput),
            errorSummary = redactString(trace.errorSummary),
            citations =
                trace.citations.map { citation ->
                    citation.copy(
                        title = redactString(citation.title) ?: citation.title,
                        url = redactString(citation.url) ?: citation.url,
                    )
                },
            attempts = trace.attempts.map { redactAttempt(it) },
        )

    companion object {
        const val MASK = "******"
        private const val REDACTED_BINARY = "[REDACTED_BINARY]"
        private const val MIN_SECRET_LENGTH = 3

        private val QUERY_KEY_REGEX =
            Regex("""([?&](?:api_?key|key)=)([^&#\s"']+)""", RegexOption.IGNORE_CASE)

        private val HEADER_GOOG_KEY_REGEX =
            Regex("""(x-goog-api-key\s*[:=]\s*["']?)([^"'\s,;}\]]+)""", RegexOption.IGNORE_CASE)

        private val BEARER_AUTH_REGEX =
            Regex("""(authorization\s*[:=]\s*["']?Bearer\s+)([^"'\s,;}\]]+)""", RegexOption.IGNORE_CASE)

        fun fromConfig(config: Map<String, Any?>): AiTraceRedactor {
            val secrets = mutableListOf<String>()
            collectSecretsFromMap(config, secrets)
            return AiTraceRedactor(secrets)
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
