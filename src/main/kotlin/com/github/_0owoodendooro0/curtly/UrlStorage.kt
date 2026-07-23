package com.github._0owoodendooro0.curtly

import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
data class UrlEntry(
    val longUrl: String,
    val expiresAt: Long? = null,
    val maxClicks: Int? = null,
    val clickCount: Int = 0
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
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ClickLog(
    val id: String = UUID.randomUUID().toString(),
    val key: String,
    val timestamp: Long = System.currentTimeMillis(),
    val ip: String? = null,
    val userAgent: String? = null,
    val referer: String? = null
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
    val logs: List<ClickLog> = emptyList()
)

interface UrlStorage {
    fun save(key: String, entry: UrlEntry)
    fun save(key: String, longUrl: String) {
        save(key, UrlEntry(longUrl))
    }
    fun get(key: String): UrlEntry?
    fun incrementClick(key: String): Boolean
    fun getAll(): Map<String, UrlEntry>
    fun remove(key: String): Boolean
    fun delete(key: String): Boolean = remove(key)

    fun recordClickLog(log: ClickLog) {}
    fun getClickLogs(key: String): List<ClickLog> = emptyList()
    fun getAllClickLogs(): List<ClickLog> = emptyList()
    fun clearClickLogs(key: String): Boolean = false
}

class InMemoryUrlStorage : UrlStorage {
    private val map = ConcurrentHashMap<String, UrlEntry>()
    private val clickLogsMap = ConcurrentHashMap<String, MutableList<ClickLog>>()

    override fun save(key: String, entry: UrlEntry) {
        map[key] = entry
    }

    override fun get(key: String): UrlEntry? = map[key]

    override fun incrementClick(key: String): Boolean {
        return map.computeIfPresent(key) { _, current ->
            current.copy(clickCount = current.clickCount + 1)
        } != null
    }

    override fun getAll(): Map<String, UrlEntry> = map.toMap()

    override fun remove(key: String): Boolean {
        clickLogsMap.remove(key)
        return map.remove(key) != null
    }

    override fun recordClickLog(log: ClickLog) {
        clickLogsMap.computeIfAbsent(log.key) { CopyOnWriteArrayList() }.add(log)
    }

    override fun getClickLogs(key: String): List<ClickLog> {
        return clickLogsMap[key]?.toList() ?: emptyList()
    }

    override fun getAllClickLogs(): List<ClickLog> {
        return clickLogsMap.values.flatten()
    }

    override fun clearClickLogs(key: String): Boolean {
        return clickLogsMap.remove(key) != null
    }
}


