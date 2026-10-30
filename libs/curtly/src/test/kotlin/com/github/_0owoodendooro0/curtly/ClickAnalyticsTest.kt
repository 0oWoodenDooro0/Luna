package com.github._0owoodendooro0.curtly

import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.*

class ClickAnalyticsTest {
    private fun createTestDatabase(): Database {
        val dbName = "test_analytics_" + System.nanoTime()
        return Database.connect(
            url = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
    }

    @Test
    fun testInMemoryClickLogOperations() {
        val storage = InMemoryUrlStorage()
        storage.save("key1", UrlEntry("https://example.com"))

        assertEquals(emptyList(), storage.getClickLogs("key1"))

        val log1 = ClickLog(key = "key1", ip = "127.0.0.1", userAgent = "Mozilla/5.0")
        val log2 = ClickLog(key = "key1", ip = "10.0.0.1", referer = "https://google.com")

        storage.recordClickLog(log1)
        storage.recordClickLog(log2)

        val logs = storage.getClickLogs("key1")
        assertEquals(2, logs.size)
        assertEquals("127.0.0.1", logs[0].ip)
        assertEquals("https://google.com", logs[1].referer)

        assertEquals(2, storage.getAllClickLogs().size)

        assertTrue(storage.clearClickLogs("key1"))
        assertEquals(emptyList(), storage.getClickLogs("key1"))
    }

    @Test
    fun testExposedStorageClickLogOperations() {
        val db = createTestDatabase()
        val storage = ExposedUrlStorage(database = db)

        storage.save("key1", UrlEntry("https://example.com"))

        val log1 = ClickLog(key = "key1", ip = "192.168.1.100", userAgent = "Chrome/100")
        storage.recordClickLog(log1)

        val logs = storage.getClickLogs("key1")
        assertEquals(1, logs.size)
        assertEquals("192.168.1.100", logs[0].ip)
        assertEquals("Chrome/100", logs[0].userAgent)

        assertTrue(storage.remove("key1"))
        assertEquals(emptyList(), storage.getClickLogs("key1"))
    }

    @Test
    fun testAuditModeDisabledByDefault() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage)

        service.shorten("https://example.com", "key1")
        service.resolve("key1")

        val entry = service.getEntry("key1")
        assertEquals(1, entry?.clickCount)
        assertEquals(emptyList(), service.getClickLogs("key1"))
    }

    @Test
    fun testAuditModeEnabledGlobal() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage, enableAuditMode = true)

        service.shorten("https://example.com", "key1")
        service.resolve("key1")

        val logs = service.getClickLogs("key1")
        assertEquals(1, logs.size)
        assertEquals("key1", logs[0].key)
    }

    @Test
    fun testResolveWithMetadata() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage, enableAuditMode = false)

        service.shorten("https://example.com", "key1")

        service.resolve(
            key = "key1",
            ip = "1.2.3.4",
            userAgent = "Safari/15",
            referer = "https://twitter.com",
        )

        val logs = service.getClickLogs("key1")
        assertEquals(1, logs.size)
        assertEquals("1.2.3.4", logs[0].ip)
        assertEquals("Safari/15", logs[0].userAgent)
        assertEquals("https://twitter.com", logs[0].referer)
    }

    @Test
    fun testAnalyticsReportGeneration() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage)

        service.shorten("https://example.com", "key1")

        val meta1 = ClickMetadata(ip = "1.1.1.1", userAgent = "Firefox/90", referer = "https://google.com")
        val meta2 = ClickMetadata(ip = "1.1.1.1", userAgent = "Firefox/90", referer = "https://bing.com")
        val meta3 = ClickMetadata(ip = "2.2.2.2", userAgent = "Chrome/110", referer = "https://google.com")

        service.resolve("key1", meta1)
        service.resolve("key1", meta2)
        service.resolve("key1", meta3)

        val analytics = service.getAnalytics("key1")
        assertNotNull(analytics)
        assertEquals("key1", analytics.key)
        assertEquals(3, analytics.totalClicks)
        assertEquals(3, analytics.auditLogsCount)
        assertEquals(2, analytics.topIps["1.1.1.1"])
        assertEquals(1, analytics.topIps["2.2.2.2"])
        assertEquals(2, analytics.topUserAgents["Firefox/90"])
        assertEquals(2, analytics.topReferers["https://google.com"])
        assertEquals(1, analytics.topReferers["https://bing.com"])

        val allAnalytics = service.getAllAnalytics()
        assertEquals(1, allAnalytics.size)
        assertTrue(allAnalytics.containsKey("key1"))
    }
}
