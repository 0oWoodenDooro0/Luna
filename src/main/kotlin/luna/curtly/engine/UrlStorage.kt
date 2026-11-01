package luna.curtly.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

interface UrlStorage {
    fun save(
        key: String,
        entry: UrlEntry,
    )

    fun save(
        key: String,
        longUrl: String,
    ) {
        save(key, UrlEntry(longUrl))
    }

    fun get(key: String): UrlEntry?

    fun incrementClick(key: String): Boolean

    fun getAll(): Map<String, UrlEntry>

    fun getByOwnerId(ownerId: String): Map<String, UrlEntry> = emptyMap()

    fun countByOwnerId(ownerId: String): Int = getByOwnerId(ownerId).size

    fun remove(key: String): Boolean

    fun delete(key: String): Boolean = remove(key)

    fun removeByOwnerId(ownerId: String): Int = 0

    fun recordClickLog(log: ClickLog) {}

    fun getClickLogs(key: String): List<ClickLog> = emptyList()

    fun getAllClickLogs(): List<ClickLog> = emptyList()

    fun clearClickLogs(key: String): Boolean = false
}

class InMemoryUrlStorage : UrlStorage {
    private val map = ConcurrentHashMap<String, UrlEntry>()
    private val clickLogsMap = ConcurrentHashMap<String, MutableList<ClickLog>>()

    override fun save(
        key: String,
        entry: UrlEntry,
    ) {
        map[key] = entry
    }

    override fun get(key: String): UrlEntry? = map[key]

    override fun incrementClick(key: String): Boolean =
        map.computeIfPresent(key) { _, current ->
            current.copy(clickCount = current.clickCount + 1)
        } != null

    override fun getAll(): Map<String, UrlEntry> = map.toMap()

    override fun getByOwnerId(ownerId: String): Map<String, UrlEntry> = map.filterValues { it.ownerId == ownerId }

    override fun countByOwnerId(ownerId: String): Int = map.values.count { it.ownerId == ownerId }

    override fun remove(key: String): Boolean {
        clickLogsMap.remove(key)
        return map.remove(key) != null
    }

    override fun removeByOwnerId(ownerId: String): Int {
        val keysToRemove = map.filterValues { it.ownerId == ownerId }.keys
        var count = 0
        for (key in keysToRemove) {
            if (remove(key)) count++
        }
        return count
    }

    override fun recordClickLog(log: ClickLog) {
        clickLogsMap.computeIfAbsent(log.key) { CopyOnWriteArrayList() }.add(log)
    }

    override fun getClickLogs(key: String): List<ClickLog> = clickLogsMap[key]?.toList() ?: emptyList()

    override fun getAllClickLogs(): List<ClickLog> = clickLogsMap.values.flatten()

    override fun clearClickLogs(key: String): Boolean = clickLogsMap.remove(key) != null
}
