package com.github._0owoodendooro0.curtly

class CurtlyService(
    private val storage: UrlStorage,
    private val keyGenerator: KeyGenerator = RandomAlphanumericKeyGenerator(),
    private val baseUrl: String = "http://localhost:8080/"
) {
    /**
     * Shortens a long URL and returns the full short URL.
     * Optionally accepts customKey, expiresInSeconds, expiresAt, and maxClicks.
     */
    fun shorten(
        longUrl: String,
        customKey: String? = null,
        expiresInSeconds: Long? = null,
        expiresAt: Long? = null,
        maxClicks: Int? = null
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
            maxClicks = maxClicks
        )

        storage.save(key, entry)

        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return "$normalizedBaseUrl$key"
    }

    /**
     * Resolves a key to the original long URL.
     * Returns null if key does not exist or if link has expired / reached max clicks.
     */
    fun resolve(key: String): String? {
        val entry = storage.get(key) ?: return null
        if (entry.isExpired()) {
            return null
        }
        storage.incrementClick(key)
        return entry.longUrl
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
}
