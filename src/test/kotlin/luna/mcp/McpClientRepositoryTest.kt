package luna.mcp

import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpClientRepositoryTest {
    private lateinit var database: Database
    private lateinit var repository: McpClientRepository

    @BeforeEach
    fun setUp() {
        val dbName = "test_client_app_${UUID.randomUUID()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
        repository = McpClientRepository(database)
    }

    @AfterEach
    fun tearDown() {
        // H2 in-memory DB cleaned up
    }

    @Test
    fun `createClient inserts client and findByClientId retrieves it`() {
        val now = System.currentTimeMillis()
        val record =
            McpClientRecord(
                clientId = "client-app-1",
                userId = "user-app-1",
                clientName = "Alice App Client",
                secretHash = "hash123",
                secretSalt = "salt123",
                createdAt = now,
                updatedAt = now,
            )

        repository.createClient(record)

        val found = repository.findByClientId("client-app-1")
        assertNotNull(found)
        assertEquals("client-app-1", found.clientId)
        assertEquals("user-app-1", found.userId)
        assertEquals("Alice App Client", found.clientName)
        assertEquals("hash123", found.secretHash)
        assertEquals("salt123", found.secretSalt)
        assertEquals(now, found.createdAt)
        assertEquals(now, found.updatedAt)
    }

    @Test
    fun `findByUserId retrieves client by user_id`() {
        val now = System.currentTimeMillis()
        val record =
            McpClientRecord(
                clientId = "client-app-2",
                userId = "user-unique-app-2",
                clientName = "Bob App Client",
                secretHash = "hash-b",
                secretSalt = "salt-b",
                createdAt = now,
                updatedAt = now,
            )

        repository.createClient(record)

        val found = repository.findByUserId("user-unique-app-2")
        assertNotNull(found)
        assertEquals("client-app-2", found.clientId)
        assertEquals("user-unique-app-2", found.userId)
    }

    @Test
    fun `findByClientId and findByUserId return null when not found`() {
        assertNull(repository.findByClientId("non-existent"))
        assertNull(repository.findByUserId("non-existent"))
    }

    @Test
    fun `inserting duplicate user_id throws unique constraint violation`() {
        val now = System.currentTimeMillis()
        val record1 =
            McpClientRecord(
                clientId = "client-app-dup-1",
                userId = "same-user-app",
                clientName = "Client 1",
                secretHash = "hash1",
                secretSalt = "salt1",
                createdAt = now,
                updatedAt = now,
            )
        val record2 =
            McpClientRecord(
                clientId = "client-app-dup-2",
                userId = "same-user-app",
                clientName = "Client 2",
                secretHash = "hash2",
                secretSalt = "salt2",
                createdAt = now + 100,
                updatedAt = now + 100,
            )

        repository.createClient(record1)

        assertThrows<Exception> {
            repository.createClient(record2)
        }
    }

    @Test
    fun `updateSecret updates secret credentials and timestamp`() {
        val now = System.currentTimeMillis()
        val record =
            McpClientRecord(
                clientId = "client-app-upd",
                userId = "user-app-upd",
                clientName = "Update Client",
                secretHash = "old-hash",
                secretSalt = "old-salt",
                createdAt = now,
                updatedAt = now,
            )
        repository.createClient(record)

        val updatedTime = now + 5000
        val success = repository.updateSecret("client-app-upd", "new-hash", "new-salt", updatedTime)
        assertTrue(success)

        val updated = repository.findByClientId("client-app-upd")
        assertNotNull(updated)
        assertEquals("new-hash", updated.secretHash)
        assertEquals("new-salt", updated.secretSalt)
        assertEquals(updatedTime, updated.updatedAt)
    }

    @Test
    fun `updateSecret returns false for non-existent client`() {
        val success = repository.updateSecret("non-existent", "new-hash", "new-salt", System.currentTimeMillis())
        assertFalse(success)
    }

    @Test
    fun `deleteClient removes client and returns true`() {
        val now = System.currentTimeMillis()
        val record =
            McpClientRecord(
                clientId = "client-app-del",
                userId = "user-app-del",
                clientName = "Delete Client",
                secretHash = "hash",
                secretSalt = "salt",
                createdAt = now,
                updatedAt = now,
            )
        repository.createClient(record)

        val deleted = repository.deleteClient("client-app-del")
        assertTrue(deleted)
        assertNull(repository.findByClientId("client-app-del"))
    }

    @Test
    fun `deleteClient returns false for non-existent client`() {
        val deleted = repository.deleteClient("non-existent")
        assertFalse(deleted)
    }
}
