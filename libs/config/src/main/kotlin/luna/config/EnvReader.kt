package luna.config

import java.io.File

/**
 * Strict, typed environment configuration reader backed by a flat string map.
 * Unset keys and blank/whitespace-only values are treated as unset.
 */
class EnvReader private constructor(
    private val values: Map<String, String>,
    val driftAudit: DriftAuditResult? = null,
) {
    /** Returns the trimmed non-blank value for [key], or `null` if unset or blank. */
    fun string(key: String): String? = values[key]?.trim()?.takeIf { it.isNotEmpty() }

    /** Returns the trimmed non-blank value for [key], or [default] if unset or blank. */
    fun string(
        key: String,
        default: String,
    ): String = string(key) ?: default

    /** Returns the trimmed non-blank value for [key], or throws [ConfigValidationException] if unset or blank. */
    fun requiredString(key: String): String = string(key) ?: throw ConfigValidationException("Missing required configuration: $key must not be blank")

    /** Returns the first non-blank value among [keys] in argument order, or `null` if all are unset or blank. */
    fun firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { string(it) }

    /** Returns the first non-blank value among [keys] in argument order, or [default] if all are unset or blank. */
    fun firstString(
        vararg keys: String,
        default: String,
    ): String = firstString(*keys) ?: default

    /** Parses [key] as an [Int], or returns [default] if unset or blank. Fails fast on malformed or out-of-range values. */
    fun int(
        key: String,
        default: Int,
        range: IntRange? = null,
    ): Int {
        val raw = string(key) ?: return default
        val parsed = raw.toIntOrNull() ?: invalid(key, "an integer", raw)
        if (range != null && parsed !in range) {
            invalid(key, "an integer in ${range.first}..${range.last}", raw)
        }
        return parsed
    }

    /** Parses [key] as a [Long], or returns [default] if unset or blank. Fails fast on malformed or out-of-range values. */
    fun long(
        key: String,
        default: Long,
        range: LongRange? = null,
    ): Long {
        val raw = string(key) ?: return default
        val parsed = raw.toLongOrNull() ?: invalid(key, "a long integer", raw)
        if (range != null && parsed !in range) {
            invalid(key, "a long integer in ${range.first}..${range.last}", raw)
        }
        return parsed
    }

    /** Parses [key] as a finite [Double], or returns [default] if unset or blank. Fails fast on malformed or out-of-range values. */
    fun double(
        key: String,
        default: Double,
        range: ClosedFloatingPointRange<Double>? = null,
    ): Double {
        val raw = string(key) ?: return default
        val parsed = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: invalid(key, "a finite number", raw)
        if (range != null && parsed !in range) {
            invalid(key, "a number in ${range.start}..${range.endInclusive}", raw)
        }
        return parsed
    }

    /** Parses [key] strictly as `true` or `false` (case-insensitive), or returns [default] if unset or blank. */
    fun boolean(
        key: String,
        default: Boolean,
    ): Boolean {
        val raw = string(key) ?: return default
        return when {
            raw.equals("true", ignoreCase = true) -> true
            raw.equals("false", ignoreCase = true) -> false
            else -> invalid(key, "'true' or 'false'", raw)
        }
    }

    /** Parses [key] as a comma-separated list of trimmed non-blank strings, or an empty list if unset or blank. */
    fun csv(key: String): List<String> {
        val raw = string(key) ?: return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Parses [key] as a valid TCP/UDP port in `1..65535`, or returns [default] if unset or blank. */
    fun port(
        key: String,
        default: Int,
    ): Int = int(key, default, PORT_RANGE)

    private fun invalid(
        key: String,
        expected: String,
        raw: String,
    ): Nothing {
        val safeValue = SecretMasker.maskValue(key, raw)
        throw ConfigValidationException("Invalid configuration for $key: expected $expected, but got '$safeValue'")
    }

    /** Returns an immutable snapshot of the raw key-value map. */
    fun toMap(): Map<String, String> = values.toMap()

    companion object {
        private val PORT_RANGE = 1..65535

        /**
         * Loads configuration with strict two-layer precedence: `.env` < [systemEnv] < [overrides],
         * optionally running [EnvSchemaAuditor.auditDrift].
         */
        fun load(
            envFile: File? = ConfigFinder.findDefaultEnvFile(),
            systemEnv: Map<String, String> = System.getenv(),
            overrides: Map<String, String>? = null,
            auditDrift: Boolean = true,
            schema: List<EnvVarSchema>? = null,
            requiredKeys: Set<String>? = null,
        ): EnvReader {
            val merged = mutableMapOf<String, String>()
            if (envFile != null && envFile.exists() && envFile.isFile) {
                merged.putAll(DotenvParser.parse(envFile))
            }
            merged.putAll(systemEnv)
            if (overrides != null) {
                merged.putAll(overrides)
            }

            val auditResult =
                if (auditDrift) {
                    val effectiveSchema = schema ?: EnvSchemaAuditor.loadDefaultSchema()
                    EnvSchemaAuditor.auditDrift(
                        config = merged,
                        schema = effectiveSchema,
                        requiredKeys = requiredKeys,
                    )
                } else {
                    null
                }

            return EnvReader(values = merged, driftAudit = auditResult)
        }

        /** Constructs an isolated in-memory [EnvReader] with zero filesystem or OS environment access. */
        fun of(map: Map<String, String> = emptyMap()): EnvReader = EnvReader(map.toMap())

        /** Constructs an isolated in-memory [EnvReader] from key-value pairs. */
        fun of(vararg pairs: Pair<String, String>): EnvReader = EnvReader(pairs.toMap())
    }
}
