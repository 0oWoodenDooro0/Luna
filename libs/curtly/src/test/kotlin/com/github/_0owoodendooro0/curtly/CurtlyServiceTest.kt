package com.github._0owoodendooro0.curtly

import kotlin.test.*

class CurtlyServiceTest {
    @Test
    fun testInMemoryUrlStorage() {
        val storage = InMemoryUrlStorage()
        assertNull(storage.get("key1"))

        storage.save("key1", "https://google.com")
        assertEquals(UrlEntry("https://google.com"), storage.get("key1"))
        assertEquals(mapOf("key1" to UrlEntry("https://google.com")), storage.getAll())

        assertTrue(storage.remove("key1"))
        assertNull(storage.get("key1"))
        assertFalse(storage.remove("key1"))
    }

    @Test
    fun testRemoveAndDeleteService() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage, baseUrl = "http://localhost/")

        service.shorten("https://google.com", "key1")
        service.shorten("https://github.com", "key2")

        assertTrue(service.remove("key1"))
        assertNull(service.getEntry("key1"))
        assertFalse(service.remove("key1"))

        assertTrue(service.delete("key2"))
        assertNull(service.getEntry("key2"))
        assertFalse(service.delete("key2"))
    }

    @Test
    fun testCurtlyServiceUrlNormalization() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage, baseUrl = "http://localhost/")

        // Tests adding https if protocol is missing
        val short1 = service.shorten("google.com")
        val key1 = short1.removePrefix("http://localhost/")
        assertEquals("https://google.com", service.resolve(key1))

        // Tests keeping protocol if it is present
        val short2 = service.shorten("http://example.com")
        val key2 = short2.removePrefix("http://localhost/")
        assertEquals("http://example.com", service.resolve(key2))
    }

    @Test
    fun testCurtlyServiceCustomKey() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage, baseUrl = "http://localhost/")

        val short = service.shorten("https://google.com", "myCustomKey")
        assertEquals("http://localhost/myCustomKey", short)
        assertEquals("https://google.com", service.resolve("myCustomKey"))

        // Custom key already in use should throw IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            service.shorten("https://yahoo.com", "myCustomKey")
        }

        // Invalid characters in custom key should throw IllegalArgumentException
        assertFailsWith<IllegalArgumentException> {
            service.shorten("https://yahoo.com", "invalid/key")
        }
    }

    @Test
    fun testExpirationByTimestamp() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage, baseUrl = "http://localhost/")

        val pastTime = System.currentTimeMillis() - 1000L
        val short = service.shorten("https://example.com", customKey = "expKey", expiresAt = pastTime)
        assertNull(service.resolve("expKey"))
    }

    @Test
    fun testMaxClicksLimit() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage, baseUrl = "http://localhost/")

        val short = service.shorten("https://example.com", customKey = "limitKey", maxClicks = 2)

        // 1st click
        assertEquals("https://example.com", service.resolve("limitKey"))
        assertEquals(1, service.getEntry("limitKey")?.clickCount)

        // 2nd click
        assertEquals("https://example.com", service.resolve("limitKey"))
        assertEquals(2, service.getEntry("limitKey")?.clickCount)

        // 3rd click (should expire as maxClicks is 2)
        assertNull(service.resolve("limitKey"))
    }

    @Test
    fun testDefaultUnlimited() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage, baseUrl = "http://localhost/")

        val short = service.shorten("https://example.com", customKey = "unlimitedKey")
        assertNull(service.getEntry("unlimitedKey")?.expiresAt)
        assertNull(service.getEntry("unlimitedKey")?.maxClicks)

        repeat(10) {
            assertEquals("https://example.com", service.resolve("unlimitedKey"))
        }
        assertEquals(10, service.getEntry("unlimitedKey")?.clickCount)
    }

    @Test
    fun testOwnerIdOperations() {
        val storage = InMemoryUrlStorage()
        val service = CurtlyService(storage, baseUrl = "http://localhost/", enableAuditMode = true)

        // Create URLs with ownerId and without ownerId
        service.shorten("https://google.com", customKey = "user1_k1", ownerId = "user1")
        service.shorten("https://github.com", customKey = "user1_k2", ownerId = "user1")
        service.shorten("https://apple.com", customKey = "user2_k1", ownerId = "user2")
        service.shorten("https://bing.com", customKey = "anon_k1")

        // Verify getEntry ownerId
        assertEquals("user1", service.getEntry("user1_k1")?.ownerId)
        assertEquals("user1", service.getEntry("user1_k2")?.ownerId)
        assertEquals("user2", service.getEntry("user2_k1")?.ownerId)
        assertNull(service.getEntry("anon_k1")?.ownerId)

        // Verify countByOwnerId
        assertEquals(2, service.countByOwnerId("user1"))
        assertEquals(1, service.countByOwnerId("user2"))
        assertEquals(0, service.countByOwnerId("non_existent_user"))

        // Verify getKeysByOwnerId
        val user1Keys = service.getKeysByOwnerId("user1")
        assertEquals(2, user1Keys.size)
        assertTrue(user1Keys.containsAll(listOf("user1_k1", "user1_k2")))

        // Verify getByOwnerId
        val user1Entries = service.getByOwnerId("user1")
        assertEquals(2, user1Entries.size)
        assertTrue(user1Entries.containsKey("user1_k1"))
        assertTrue(user1Entries.containsKey("user1_k2"))

        // Verify getAnalyticsByOwnerId
        service.resolve("user1_k1")
        val user1Analytics = service.getAnalyticsByOwnerId("user1")
        assertEquals(2, user1Analytics.size)
        assertEquals(1, user1Analytics["user1_k1"]?.totalClicks)

        // Verify removeByOwnerId / deleteByOwnerId
        val removedCount = service.deleteByOwnerId("user1")
        assertEquals(2, removedCount)
        assertEquals(0, service.countByOwnerId("user1"))
        assertNull(service.getEntry("user1_k1"))
        assertNull(service.getEntry("user1_k2"))

        // Verify user2 and anon entries are untouched
        assertNotNull(service.getEntry("user2_k1"))
        assertNotNull(service.getEntry("anon_k1"))
    }
}
