package luna.mcp.server

import kotlinx.coroutines.channels.Channel

data class McpSessionEntry(
    val channel: Channel<String>,
    val clientId: String,
)

class McpSessionManager(
    private val maxSessions: Int = MAX_DEFAULT_SESSIONS,
) {
    private val lock = Any()
    private val sessions: MutableMap<String, McpSessionEntry> =
        object : LinkedHashMap<String, McpSessionEntry>(maxSessions, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, McpSessionEntry>?): Boolean {
                if (size > maxSessions) {
                    eldest?.value?.channel?.close()
                    return true
                }
                return false
            }
        }

    fun registerSession(
        sessionId: String,
        channel: Channel<String>,
        clientId: String = "luna-mcp-client",
    ) {
        synchronized(lock) {
            sessions[sessionId] = McpSessionEntry(channel, clientId)
        }
    }

    fun getClientId(sessionId: String): String? =
        synchronized(lock) {
            sessions[sessionId]?.clientId
        }

    fun removeSession(sessionId: String) {
        synchronized(lock) {
            sessions.remove(sessionId)?.channel?.close()
        }
    }

    suspend fun sendToSession(
        sessionId: String,
        message: String,
    ): Boolean {
        val entry = synchronized(lock) { sessions[sessionId] } ?: return false
        return runCatching {
            entry.channel.send(message)
            true
        }.getOrDefault(false)
    }

    fun hasSession(sessionId: String): Boolean = synchronized(lock) { sessions.containsKey(sessionId) }

    val activeSessionCount: Int
        get() = synchronized(lock) { sessions.size }

    companion object {
        const val MAX_DEFAULT_SESSIONS = 100
    }
}
