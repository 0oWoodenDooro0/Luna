package luna.mcp.server

import kotlinx.coroutines.channels.Channel

class McpSessionManager(
    private val maxSessions: Int = MAX_DEFAULT_SESSIONS,
) {
    private val lock = Any()
    private val sessions: MutableMap<String, Channel<String>> =
        object : LinkedHashMap<String, Channel<String>>(maxSessions, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Channel<String>>?): Boolean {
                if (size > maxSessions) {
                    eldest?.value?.close()
                    return true
                }
                return false
            }
        }

    fun registerSession(
        sessionId: String,
        channel: Channel<String>,
    ) {
        synchronized(lock) {
            sessions[sessionId] = channel
        }
    }

    fun removeSession(sessionId: String) {
        synchronized(lock) {
            sessions.remove(sessionId)?.close()
        }
    }

    suspend fun sendToSession(
        sessionId: String,
        message: String,
    ): Boolean {
        val channel = synchronized(lock) { sessions[sessionId] } ?: return false
        return runCatching {
            channel.send(message)
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
