package com.github._0owoodendooro0.curtly

import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.test.*

class ExposedUrlStorageTest {

    private fun createTestDatabase(): Database {
        val dbName = "test_" + System.nanoTime()
        return Database.connect(
            url = "jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )
    }

    @Test
    fun testExposedUrlStorageBasicOperations() {
        val db = createTestDatabase()
        val storage = ExposedUrlStorage(database = db)

        assertNull(storage.get("key1"))

        val entry = UrlEntry("https://google.com", maxClicks = 5, expiresAt = 123456789L)
        storage.save("key1", entry)

        val retrieved = storage.get("key1")
        assertNotNull(retrieved)
        assertEquals("https://google.com", retrieved.longUrl)
        assertEquals(5, retrieved.maxClicks)
        assertEquals(123456789L, retrieved.expiresAt)
        assertEquals(0, retrieved.clickCount)

        val all = storage.getAll()
        assertEquals(1, all.size)
        assertEquals(entry, all["key1"])
    }

    @Test
    fun testIncrementClick() {
        val db = createTestDatabase()
        val storage = ExposedUrlStorage(database = db)

        assertFalse(storage.incrementClick("non_existent"))

        storage.save("key1", UrlEntry("https://kotlinlang.org"))
        assertTrue(storage.incrementClick("key1"))
        assertEquals(1, storage.get("key1")?.clickCount)

        assertTrue(storage.incrementClick("key1"))
        assertEquals(2, storage.get("key1")?.clickCount)
    }

    @Test
    fun testUpsert() {
        val db = createTestDatabase()
        val storage = ExposedUrlStorage(database = db)

        storage.save("key1", UrlEntry("https://google.com"))
        assertEquals("https://google.com", storage.get("key1")?.longUrl)

        storage.save("key1", UrlEntry("https://bing.com", clickCount = 3))
        assertEquals("https://bing.com", storage.get("key1")?.longUrl)
        assertEquals(3, storage.get("key1")?.clickCount)
    }

    @Test
    fun testCurtlyServiceWithExposedUrlStorage() {
        val db = createTestDatabase()
        val storage = ExposedUrlStorage(database = db)
        val service = CurtlyService(storage, baseUrl = "http://localhost/")

        val shortUrl = service.shorten(
            longUrl = "https://example.com/long/path",
            customKey = "exposedKey",
            maxClicks = 2
        )
        assertEquals("http://localhost/exposedKey", shortUrl)

        // 1st click
        assertEquals("https://example.com/long/path", service.resolve("exposedKey"))
        assertEquals(1, service.getEntry("exposedKey")?.clickCount)

        // 2nd click
        assertEquals("https://example.com/long/path", service.resolve("exposedKey"))
        assertEquals(2, service.getEntry("exposedKey")?.clickCount)

        // 3rd click -> expired
        assertNull(service.resolve("exposedKey"))
    }

    @Test
    fun testRemove() {
        val db = createTestDatabase()
        val storage = ExposedUrlStorage(database = db)

        assertFalse(storage.remove("non_existent"))

        storage.save("key1", UrlEntry("https://google.com"))
        assertNotNull(storage.get("key1"))

        assertTrue(storage.remove("key1"))
        assertNull(storage.get("key1"))
        assertFalse(storage.remove("key1"))
    }

    @Test
    fun testExposedUrlStorageOwnerId() {
        val db = createTestDatabase()
        val storage = ExposedUrlStorage(database = db)

        val entry1 = UrlEntry("https://google.com", ownerId = "org_1")
        val entry2 = UrlEntry("https://github.com", ownerId = "org_1")
        val entry3 = UrlEntry("https://apple.com", ownerId = "org_2")
        val entry4 = UrlEntry("https://bing.com") // ownerId is null

        storage.save("key1", entry1)
        storage.save("key2", entry2)
        storage.save("key3", entry3)
        storage.save("key4", entry4)

        // Verify retrieval
        assertEquals("org_1", storage.get("key1")?.ownerId)
        assertEquals("org_1", storage.get("key2")?.ownerId)
        assertEquals("org_2", storage.get("key3")?.ownerId)
        assertNull(storage.get("key4")?.ownerId)

        // Verify getByOwnerId
        val org1Map = storage.getByOwnerId("org_1")
        assertEquals(2, org1Map.size)
        assertTrue(org1Map.containsKey("key1"))
        assertTrue(org1Map.containsKey("key2"))

        // Verify countByOwnerId
        assertEquals(2, storage.countByOwnerId("org_1"))
        assertEquals(1, storage.countByOwnerId("org_2"))
        assertEquals(0, storage.countByOwnerId("org_3"))

        // Verify removeByOwnerId
        val removed = storage.removeByOwnerId("org_1")
        assertEquals(2, removed)
        assertEquals(0, storage.countByOwnerId("org_1"))
        assertNull(storage.get("key1"))
        assertNull(storage.get("key2"))
        assertNotNull(storage.get("key3"))
        assertNotNull(storage.get("key4"))
    }
}
