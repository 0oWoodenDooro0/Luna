package com.github._0owoodendooro0.curtly

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CurtlyService(
    private val storage: UrlStorage,
    private val keyGenerator: KeyGenerator = RandomAlphanumericKeyGenerator(),
    private val baseUrl: String = "http://localhost:8080/",
    val enableAuditMode: Boolean = false
) {
    /**
     * Shortens a long URL and returns the full short URL.
     * Optionally accepts customKey, expiresInSeconds, expiresAt, maxClicks, and ownerId.
     */
    fun shorten(
        longUrl: String,
        customKey: String? = null,
        expiresInSeconds: Long? = null,
        expiresAt: Long? = null,
        maxClicks: Int? = null,
        ownerId: String? = null
    ): String {
        require(expiresInSeconds == null || expiresInSeconds > 0) {
            "expiresInSeconds must be positive"
        }
        require(maxClicks == null || maxClicks > 0) {
            "maxClicks must be positive"
        }

        // Basic normalization (make sure it starts with http or https)
        val normalizedUrl = if (!longUrl.startsWith("http://") && !longUrl.startsWith("https://")) {
            "https://$longUrl"
        } else {
            longUrl
        }

        val key = if (customKey != null) {
            require(customKey.matches(Regex("[a-zA-Z0-9_-]+"))) { 
                "Custom key must contain only letters, numbers, hyphens or underscores" 
            }
            require(storage.get(customKey) == null) {
                "Custom key '$customKey' is already in use"
            }
            customKey
        } else {
            var generatedKey = keyGenerator.generate()
            // Avoid collisions
            while (storage.get(generatedKey) != null) {
                generatedKey = keyGenerator.generate()
            }
            generatedKey
        }

        val calculatedExpiresAt = when {
            expiresInSeconds != null -> System.currentTimeMillis() + expiresInSeconds * 1000L
            expiresAt != null -> expiresAt
            else -> null
        }

        val entry = UrlEntry(
            longUrl = normalizedUrl,
            expiresAt = calculatedExpiresAt,
            maxClicks = maxClicks,
            ownerId = ownerId
        )

        storage.save(key, entry)

        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return "$normalizedBaseUrl$key"
    }

    /**
     * Resolves a key to the original long URL.
     * Optionally records click metadata (IP, User-Agent, Referer, Timestamp) if audit mode is enabled or metadata is provided.
     * Returns null if key does not exist or if link has expired / reached max clicks.
     */
    fun resolve(
        key: String,
        metadata: ClickMetadata? = null
    ): String? {
        val entry = storage.get(key) ?: return null
        if (entry.isExpired()) {
            return null
        }

        storage.incrementClick(key)

        val shouldRecordAudit = enableAuditMode || metadata != null
        if (shouldRecordAudit) {
            val log = ClickLog(
                key = key,
                timestamp = metadata?.timestamp ?: System.currentTimeMillis(),
                ip = metadata?.ip,
                userAgent = metadata?.userAgent,
                referer = metadata?.referer
            )
            storage.recordClickLog(log)
        }

        return entry.longUrl
    }

    /**
     * Resolves a key to the original long URL with individual metadata parameters.
     */
    fun resolve(
        key: String,
        ip: String? = null,
        userAgent: String? = null,
        referer: String? = null
    ): String? {
        val hasMetadata = ip != null || userAgent != null || referer != null
        val metadata = if (hasMetadata || enableAuditMode) {
            ClickMetadata(ip = ip, userAgent = userAgent, referer = referer)
        } else {
            null
        }
        return resolve(key, metadata)
    }

    /**
     * Gets stored URL entry details by key without incrementing click count.
     */
    fun getEntry(key: String): UrlEntry? {
        return storage.get(key)
    }

    /**
     * Gets all stored URL entries.
     */
    fun getAll(): Map<String, UrlEntry> {
        return storage.getAll()
    }

    /**
     * Gets all URL entries created by a specific ownerId.
     */
    fun getByOwnerId(ownerId: String): Map<String, UrlEntry> {
        return storage.getByOwnerId(ownerId)
    }

    /**
     * Gets all short URL keys created by a specific ownerId.
     */
    fun getKeysByOwnerId(ownerId: String): List<String> {
        return storage.getByOwnerId(ownerId).keys.toList()
    }

    /**
     * Counts the total number of short URLs created by a specific ownerId.
     */
    fun countByOwnerId(ownerId: String): Int {
        return storage.countByOwnerId(ownerId)
    }

    /**
     * Removes a stored short URL entry by key.
     * Returns true if entry existed and was removed, false otherwise.
     */
    fun remove(key: String): Boolean {
        return storage.remove(key)
    }

    /**
     * Alias for remove. Removes a stored short URL entry by key.
     */
    fun delete(key: String): Boolean {
        return storage.delete(key)
    }

    /**
     * Removes all short URLs created by a specific ownerId.
     * Returns the count of entries removed.
     */
    fun removeByOwnerId(ownerId: String): Int {
        return storage.removeByOwnerId(ownerId)
    }

    /**
     * Alias for removeByOwnerId. Removes all short URLs created by a specific ownerId.
     */
    fun deleteByOwnerId(ownerId: String): Int {
        return removeByOwnerId(ownerId)
    }

    /**
     * Retrieves all recorded click logs for a specific key.
     */
    fun getClickLogs(key: String): List<ClickLog> {
        return storage.getClickLogs(key)
    }

    /**
     * Retrieves all recorded click logs across all short URLs.
     */
    fun getAllClickLogs(): List<ClickLog> {
        return storage.getAllClickLogs()
    }

    /**
     * Generates a ClickAnalytics report for a specific short link key.
     * Returns null if the key does not exist.
     */
    fun getAnalytics(key: String): ClickAnalytics? {
        val entry = storage.get(key) ?: return null
        val logs = storage.getClickLogs(key)

        val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault())

        val clicksByDate = logs.groupingBy { log ->
            dateFormat.format(Instant.ofEpochMilli(log.timestamp))
        }.eachCount()

        val topUserAgents = logs.mapNotNull { it.userAgent }.groupingBy { it }.eachCount()
        val topReferers = logs.mapNotNull { it.referer }.groupingBy { it }.eachCount()
        val topIps = logs.mapNotNull { it.ip }.groupingBy { it }.eachCount()

        return ClickAnalytics(
            key = key,
            totalClicks = entry.clickCount,
            auditLogsCount = logs.size,
            clicksByDate = clicksByDate,
            topUserAgents = topUserAgents,
            topReferers = topReferers,
            topIps = topIps,
            logs = logs
        )
    }

    /**
     * Generates a map of ClickAnalytics reports for all stored short URL keys.
     */
    fun getAllAnalytics(): Map<String, ClickAnalytics> {
        val allEntries = storage.getAll()
        return allEntries.keys.mapNotNull { key ->
            getAnalytics(key)?.let { key to it }
        }.toMap()
    }

    /**
     * Generates a map of ClickAnalytics reports for all short URLs owned by a specific ownerId.
     */
    fun getAnalyticsByOwnerId(ownerId: String): Map<String, ClickAnalytics> {
        val ownerEntries = storage.getByOwnerId(ownerId)
        return ownerEntries.keys.mapNotNull { key ->
            getAnalytics(key)?.let { key to it }
        }.toMap()
    }

    /**
     * Clears click logs for a specific key.
     */
    fun clearClickLogs(key: String): Boolean {
        return storage.clearClickLogs(key)
    }
}

