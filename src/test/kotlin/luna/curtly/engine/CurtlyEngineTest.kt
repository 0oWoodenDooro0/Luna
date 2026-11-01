package luna.curtly.engine

import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurtlyEngineTest {
    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:engine_test_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    @Test
    fun `UrlEntry detects expiration and max click bounds`() {
        val entry = UrlEntry("https://example.com", maxClicks = 2, clickCount = 1)
        assertFalse(entry.isExpired())

        val expiredClicks = entry.copy(clickCount = 2)
        assertTrue(expiredClicks.isExpired())

        val timeExpired = UrlEntry("https://example.com", expiresAt = 1000L)
        assertTrue(timeExpired.isExpired(now = 2000L))
        assertFalse(timeExpired.isExpired(now = 500L))
    }

    @Test
    fun `InMemoryUrlStorage and CurtlyService shorten and resolve urls with analytics`() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage = storage, baseUrl = "https://luna.test/s/", enableAuditMode = true)

        val shortUrl =
            service.shorten(
                longUrl = "example.com/docs",
                customKey = "docs-custom",
                ownerId = "owner-1",
            )
        assertEquals("https://luna.test/s/docs-custom", shortUrl)

        val resolved =
            service.resolve(
                key = "docs-custom",
                ip = "127.0.0.1",
                userAgent = "Mozilla/5.0",
                referer = "https://google.com",
            )
        assertEquals("https://example.com/docs", resolved)

        val analytics = service.getAnalytics("docs-custom")
        assertNotNull(analytics)
        assertEquals(1, analytics.totalClicks)
        assertEquals(1, analytics.topUserAgents["Mozilla/5.0"])
        assertEquals(1, analytics.topReferers["https://google.com"])
        assertEquals(1, analytics.topIps["127.0.0.1"])

        val ownerUrls = service.getByOwnerId("owner-1")
        assertEquals(1, ownerUrls.size)
        assertTrue(ownerUrls.containsKey("docs-custom"))
    }

    @Test
    fun `ExposedUrlStorage persists and queries url entries and click logs in relational database`() {
        val db = createTestDb()
        val storage = ExposedUrlStorage(database = db, autoCreateTable = true)
        val service = CurtlyService(storage = storage, baseUrl = "https://luna.test/s/", enableAuditMode = true)

        val url = service.shorten(longUrl = "https://kotlinlang.org", ownerId = "dev-1")
        val key = url.substringAfterLast("/")

        val resolved = service.resolve(key, ip = "10.0.0.1")
        assertEquals("https://kotlinlang.org", resolved)

        val logs = storage.getClickLogs(key)
        assertEquals(1, logs.size)
        assertEquals("10.0.0.1", logs.first().ip)

        assertEquals(1, storage.countByOwnerId("dev-1"))
        assertTrue(storage.remove(key))
        assertNull(storage.get(key))
    }

    @Test
    fun `CurtlyWebPage renders HTML shortener markup`() {
        val html = CurtlyWebPage.getHtml(apiEndpoint = "/api/shorten", title = "Test Shortener")
        assertTrue(html.contains("<title>Test Shortener</title>"))
        assertTrue(html.contains("id=\"shorten-form\""))
        assertTrue(html.contains("/api/shorten"))
    }
}
