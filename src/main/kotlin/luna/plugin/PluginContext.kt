package luna.plugin

import dev.kord.core.Kord
import io.ktor.server.routing.Routing
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The unified runtime boundary injected by Luna Host into each plugin during initialization,
 * providing managed access to Kord, Ktor Routing, the database, and type-safe plugin configurations.
 */
interface PluginContext {
    val kord: Kord
    val routing: Routing
    val database: Database

    /**
     * Retrieves a configuration property associated with the given [key], casting or converting
     * to the requested [clazz], or returns null if not configured or incompatible.
     */
    fun <T> config(
        key: String,
        clazz: Class<T>,
    ): T?
}

/**
 * Type-safe reified convenience accessor for plugin configuration lookup.
 */
inline fun <reified T> PluginContext.config(key: String): T? = config(key, T::class.java)

/**
 * Default implementation of [PluginContext] backed by capability references and a configuration resolver.
 */
class DefaultPluginContext(
    override val kord: Kord,
    override val routing: Routing,
    override val database: Database,
    private val configResolver: (String, Class<*>) -> Any?,
) : PluginContext {
    constructor(
        kord: Kord,
        routing: Routing,
        database: Database,
        configMap: Map<String, Any?> = emptyMap(),
    ) : this(
        kord = kord,
        routing = routing,
        database = database,
        configResolver = { key, clazz -> resolveFromMap(configMap, key, clazz) },
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T> config(
        key: String,
        clazz: Class<T>,
    ): T? {
        val resolved = configResolver(key, clazz) ?: return null
        return if (clazz.isInstance(resolved)) {
            clazz.cast(resolved)
        } else {
            castOrConvert(resolved, clazz)
        }
    }

    companion object {
        private fun resolveFromMap(
            map: Map<String, Any?>,
            key: String,
            clazz: Class<*>,
        ): Any? {
            val value = map[key] ?: return null
            return castOrConvert(value, clazz)
        }

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
                    String::class.java -> str
                    Int::class.javaObjectType, Int::class.javaPrimitiveType -> (value as? Number)?.toInt() ?: str.toIntOrNull()
                    Long::class.javaObjectType, Long::class.javaPrimitiveType -> (value as? Number)?.toLong() ?: str.toLongOrNull()
                    Double::class.javaObjectType, Double::class.javaPrimitiveType -> (value as? Number)?.toDouble() ?: str.toDoubleOrNull()
                    Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType -> (value as? Boolean) ?: str.toBooleanStrictOrNull()
                    else -> null
                }
            return if (converted != null && clazz.isInstance(converted)) {
                clazz.cast(converted)
            } else {
                null
            }
        }
    }
}
