package luna.curtly.engine

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class UrlEntry(
    val longUrl: String,
    val expiresAt: Long? = null,
    val maxClicks: Int? = null,
    val clickCount: Int = 0,
    val ownerId: String? = null,
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        if (expiresAt != null && now >= expiresAt) return true
        if (maxClicks != null && clickCount >= maxClicks) return true
        return false
    }
}

@Serializable
data class ClickMetadata(
    val ip: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class ClickLog(
    val id: String = UUID.randomUUID().toString(),
    val key: String,
    val timestamp: Long = System.currentTimeMillis(),
    val ip: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
)

@Serializable
data class ClickAnalytics(
    val key: String,
    val totalClicks: Int,
    val auditLogsCount: Int,
    val clicksByDate: Map<String, Int> = emptyMap(),
    val topUserAgents: Map<String, Int> = emptyMap(),
    val topReferers: Map<String, Int> = emptyMap(),
    val topIps: Map<String, Int> = emptyMap(),
    val logs: List<ClickLog> = emptyList(),
)
