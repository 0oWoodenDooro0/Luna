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
}
