package luna.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import luna.config.EnvReader
import luna.config.SecretMasker
import luna.core.SecretRedactor

/**
 * Scrubs API keys, sensitive headers, and configured secret values from AI Trace payloads
 * before serialization, delegating string and credential masking to [SecretRedactor].
 */
class AiTraceRedactor private constructor(
    private val secretRedactor: SecretRedactor,
) {
    constructor(explicitSecrets: Collection<String> = emptyList()) : this(
        SecretRedactor(explicitSecrets = explicitSecrets, redactInlineKeyValues = false),
    )

    fun withExtraSecrets(extraSecrets: Collection<String>): AiTraceRedactor {
        val updated = secretRedactor.withExtraSecrets(extraSecrets)
        return if (updated === secretRedactor) this else AiTraceRedactor(updated)
    }

    fun redactString(input: String?): String? = secretRedactor.redact(input)

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
                    req.copy(
                        payload = redactJsonElement(req.payload),
                        enabledTools = req.enabledTools.map { redactString(it) ?: it },
                    )
                },
            responseRaw = redactString(attempt.responseRaw),
            errorMessage = redactString(attempt.errorMessage),
            urlContextResults =
                attempt.urlContextResults.map { entry ->
                    entry.copy(
                        retrievedUrl = redactString(entry.retrievedUrl) ?: entry.retrievedUrl,
                        status = redactString(entry.status) ?: entry.status,
                    )
                },
            googleSearchQueries = attempt.googleSearchQueries.map { redactString(it) ?: it },
            rawCitations = attempt.rawCitations.map(::redactCitation),
            filteredCitations = attempt.filteredCitations.map(::redactCitation),
        )

    fun redactTrace(trace: AiTraceRecord): AiTraceRecord =
        trace.copy(
            finalOutput = redactString(trace.finalOutput),
            errorSummary = redactString(trace.errorSummary),
            citations = trace.citations.map(::redactCitation),
            attempts = trace.attempts.map { redactAttempt(it) },
            circadianPeriod = redactString(trace.circadianPeriod),
            personaMood = redactString(trace.personaMood),
        )

    private fun redactCitation(citation: GroundingCitation): GroundingCitation =
        citation.copy(
            title = redactString(citation.title) ?: citation.title,
            url = redactString(citation.url) ?: citation.url,
        )

    companion object {
        const val MASK = SecretRedactor.MASK
        private const val REDACTED_BINARY = "[REDACTED_BINARY]"

        fun sanitizeEndpoint(endpoint: String): String = SecretRedactor.sanitizeEndpoint(endpoint)

        fun from(env: EnvReader): AiTraceRedactor = AiTraceRedactor(SecretRedactor.from(env, redactInlineKeyValues = false))

        fun fromConfig(config: Map<String, Any?>): AiTraceRedactor = AiTraceRedactor(SecretRedactor.fromConfig(config, redactInlineKeyValues = false))
    }
}
