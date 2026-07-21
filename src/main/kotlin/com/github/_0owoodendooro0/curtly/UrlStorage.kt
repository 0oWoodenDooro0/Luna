package com.github._0owoodendooro0.curtly

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
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
}

class FileUrlStorage(private val file: File) : UrlStorage {
    private val map = ConcurrentHashMap<String, UrlEntry>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    @Synchronized
    private fun load() {
        if (file.exists()) {
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val rawValue = parts[1].trim()
                        runCatching {
                            json.decodeFromString<UrlEntry>(rawValue)
                        }.onSuccess { entry ->
                            map[key] = entry
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    override fun save(key: String, entry: UrlEntry) {
        map[key] = entry
        file.parentFile?.mkdirs()
        val jsonString = json.encodeToString(entry)
        file.appendText("$key=$jsonString\n")
    }

    @Synchronized
    override fun incrementClick(key: String): Boolean {
        val current = map[key] ?: return false
        val updated = current.copy(clickCount = current.clickCount + 1)
        save(key, updated)
        return true
    }

    override fun get(key: String): UrlEntry? = map[key]

    override fun getAll(): Map<String, UrlEntry> = map.toMap()
}
