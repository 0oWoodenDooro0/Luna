package luna.plugin

import dev.kord.core.Kord
import io.ktor.server.routing.Routing
import luna.core.ai.AiTraceRecorder
import luna.core.ai.NoOpAiTraceRecorder
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The unified runtime boundary injected by Luna Host into each plugin during initialization,
 * providing managed access to Kord, Ktor Routing, the database, and type-safe plugin configurations.
 */
interface PluginContext {
    val kord: Kord
    val routing: Routing
    val database: Database
    val aiTraceRecorder: AiTraceRecorder
        get() = NoOpAiTraceRecorder

    /**
     * Retrieves a configuration property associated with the given [key], casting or converting
     * to the requested [clazz], or returns null if not configured or incompatible.
     */
    fun <T> config(
        key: String,
        clazz: Class<T>,
    ): T?

    /**
     * Retrieves a configuration property associated with the given [key], returning [defaultValue]
     * if not configured, blank/whitespace-only key, or conversion fails.
     */
    fun <T> config(
        key: String,
        defaultValue: T,
    ): T =
        if (defaultValue != null) {
            @Suppress("UNCHECKED_CAST")
            config(key, defaultValue::class.java as Class<T>) ?: defaultValue
        } else {
            @Suppress("UNCHECKED_CAST")
            (config(key, Any::class.java) as? T) ?: defaultValue
        }
}

/**
 * Type-safe reified convenience accessor for plugin configuration lookup.
 */
inline fun <reified T> PluginContext.config(key: String): T? = config(key, T::class.java)

/**
 * Type-safe reified convenience accessor for plugin configuration lookup with typed default fallback.
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
inline fun <reified T : Any> PluginContext.config(
    key: String,
    defaultValue: T,
): T = config(key, T::class.java) ?: defaultValue

/**
 * Default implementation of [PluginContext] backed by capability references and a configuration resolver.
 */
class DefaultPluginContext(
    override val kord: Kord,
    override val routing: Routing,
    override val database: Database,
    private val configResolver: (String, Class<*>) -> Any?,
    override val aiTraceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
) : PluginContext {
    constructor(
        kord: Kord,
        routing: Routing,
        database: Database,
        configMap: Map<String, Any?> = emptyMap(),
        aiTraceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
    ) : this(
        kord = kord,
        routing = routing,
        database = database,
        configResolver = { key, clazz -> resolveFromMap(configMap, key, clazz) },
        aiTraceRecorder = aiTraceRecorder,
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T> config(
        key: String,
        clazz: Class<T>,
    ): T? {
        if (key.isBlank()) return null
        val candidates = resolveCandidateKeys(key)
        for (candidate in candidates) {
            val resolved = configResolver(candidate, clazz) ?: continue
            val converted = castOrConvert(resolved, clazz)
            if (converted != null) {
                return converted
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> config(
        key: String,
        defaultValue: T,
    ): T {
        if (key.isBlank()) return defaultValue
        if (defaultValue == null) {
            return (config(key, Any::class.java) as? T) ?: defaultValue
        }
        val clazz = defaultValue::class.java as Class<T>
        return config(key, clazz) ?: defaultValue
    }

    companion object {
        private val CAMEL_CASE_REGEX = Regex("(?<=[a-z0-9])(?=[A-Z])")

        private fun toSnakeCase(input: String): String =
            input
                .replace(CAMEL_CASE_REGEX, "_")
                .replace('-', '_')
                .replace('.', '_')

        internal fun resolveCandidateKeys(key: String): List<String> {
            if (key.isBlank()) return emptyList()

            val candidates = mutableListOf<String>()
            // 1. Exact key (e.g. curtly.baseUrl)
            candidates.add(key)

            if (key.contains('.')) {
                val parts = key.split('.')

                // 2. Lowercase dotted snake_case (e.g. curtly.baseUrl -> curtly.base_url)
                val dottedLowerSnake =
                    parts.joinToString(".") { part ->
                        toSnakeCase(part).lowercase()
                    }
                candidates.add(dottedLowerSnake)

                // 3. Uppercase snake_case (e.g. curtly.baseUrl -> CURTLY_BASE_URL)
                val upperSnake = toSnakeCase(key).uppercase()
                candidates.add(upperSnake)

                // Known plugin namespace aliases (e.g. summarizer <-> summary)
                val prefix = parts.first()
                val rest = parts.drop(1).joinToString(".")
                val aliasPrefixes =
                    when (prefix.lowercase()) {
                        "summarizer" -> listOf("summary")
                        "summary" -> listOf("summarizer")
                        else -> emptyList()
                    }
                for (alias in aliasPrefixes) {
                    candidates.add(toSnakeCase("$alias.$rest").uppercase())
                }

                // 4. PLUGINS_<NAME>_<PROPERTY> (e.g. PLUGINS_CURTLY_BASE_URL)
                if (!upperSnake.startsWith("PLUGINS_")) {
                    candidates.add("PLUGINS_$upperSnake")
                    for (alias in aliasPrefixes) {
                        candidates.add("PLUGINS_${toSnakeCase("$alias.$rest").uppercase()}")
                    }
                }

                // 5. Simple property name uppercase (e.g. curtly.baseUrl -> BASE_URL, summarizer.emoji -> EMOJI)
                // Guard: External service URLs (e.g. gemini.baseUrl, chatter.baseUrl) must never
                // fall back to the bot's own public web server BASE_URL.
                val simpleProp = parts.last()
                val upperSimple = toSnakeCase(simpleProp).uppercase()
                val isBaseUrlCollision =
                    upperSimple == "BASE_URL" &&
                        !prefix.equals("curtly", ignoreCase = true) &&
                        !prefix.equals("luna", ignoreCase = true)
                if (!isBaseUrlCollision) {
                    candidates.add(upperSimple)
                }

                // If key starts with "plugins.", also check without "plugins." prefix
                if (prefix.equals("plugins", ignoreCase = true) && parts.size > 2) {
                    val withoutPlugins = parts.drop(1).joinToString(".")
                    candidates.add(toSnakeCase(withoutPlugins).uppercase())
                }
            } else {
                val upperSnake = toSnakeCase(key).uppercase()
                if (upperSnake != key) {
                    candidates.add(upperSnake)
                }
            }

            return candidates.distinct()
        }

        private fun resolveFromMap(
            map: Map<String, Any?>,
            key: String,
            clazz: Class<*>,
        ): Any? = map[key]

        @Suppress("UNCHECKED_CAST")
        private fun <T> castOrConvert(
            value: Any,
            clazz: Class<T>,
        ): T? {
            if (clazz.isInstance(value)) {
                return clazz.cast(value)
            }
            val str = value.toString()
            val converted: Any? =
                when (clazz) {
                    String::class.java -> {
                        str
                    }

                    Int::class.javaObjectType, Int::class.javaPrimitiveType -> {
                        (value as? Number)?.toInt() ?: str.trim().toIntOrNull()
                    }

                    Long::class.javaObjectType, Long::class.javaPrimitiveType -> {
                        (value as? Number)?.toLong() ?: str.trim().toLongOrNull()
                    }

                    Double::class.javaObjectType, Double::class.javaPrimitiveType -> {
                        (value as? Number)?.toDouble() ?: str.trim().toDoubleOrNull()
                    }

                    Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType -> {
                        (value as? Boolean) ?: str.trim().toBooleanStrictOrNull()
                    }

                    else -> {
                        null
                    }
                }
            return if (converted != null && (clazz.isInstance(converted) || isBoxedInstance(clazz, converted))) {
                converted as T
            } else {
                null
            }
        }

        private fun isBoxedInstance(
            clazz: Class<*>,
            value: Any,
        ): Boolean =
            when (clazz) {
                java.lang.Integer.TYPE -> value is Int
                java.lang.Long.TYPE -> value is Long
                java.lang.Double.TYPE -> value is Double
                java.lang.Boolean.TYPE -> value is Boolean
                java.lang.Float.TYPE -> value is Float
                java.lang.Short.TYPE -> value is Short
                java.lang.Byte.TYPE -> value is Byte
                java.lang.Character.TYPE -> value is Char
                else -> false
            }
    }
}
