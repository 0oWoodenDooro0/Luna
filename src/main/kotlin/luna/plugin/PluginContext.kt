package luna.plugin

import dev.kord.core.Kord
import io.ktor.server.routing.Routing
import luna.config.EnvReader
import luna.core.ai.AiTraceRecorder
import luna.core.ai.NoOpAiTraceRecorder
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * The unified runtime boundary injected by Luna Host into each plugin during initialization,
 * providing managed access to Kord, Ktor Routing, the database, and typed [EnvReader] configuration.
 */
interface PluginContext {
    val kord: Kord
    val routing: Routing
    val database: Database
    val aiTraceRecorder: AiTraceRecorder
        get() = NoOpAiTraceRecorder
    val env: EnvReader
        get() = EnvReader.of()
}

/**
 * Default implementation of [PluginContext] backed by capability references and [EnvReader].
 */
class DefaultPluginContext(
    override val kord: Kord,
    override val routing: Routing,
    override val database: Database,
    override val aiTraceRecorder: AiTraceRecorder = NoOpAiTraceRecorder,
    override val env: EnvReader = EnvReader.of(),
) : PluginContext
