package com.github._0owoodendooro0.curtly

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

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
}

class InMemoryUrlStorage : UrlStorage {
    private val map = ConcurrentHashMap<String, UrlEntry>()

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

    override fun remove(key: String): Boolean = map.remove(key) != null
}

