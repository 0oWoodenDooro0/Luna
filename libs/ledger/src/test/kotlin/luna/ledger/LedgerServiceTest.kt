package luna.ledger

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LedgerServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbPath: Path
    private lateinit var service: LedgerService
    private val fixedInstant = Instant.parse("2026-11-27T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        dbPath = tempDir.resolve("ledger.db")
        val database = LedgerDatabase.connect(dbPath.toString())
        val repository = LedgerRepository(database)
        service = LedgerService(repository, fixedClock)
    }

    @Test
    fun `connect configures SQLite WAL mode and busy_timeout`() {
        val database = LedgerDatabase.connect(dbPath.toString())
        var journalMode = ""
        var busyTimeout = -1
        transaction(database) {
            exec("PRAGMA journal_mode;") { rs ->
                if (rs.next()) journalMode = rs.getString(1)
            }
            exec("PRAGMA busy_timeout;") { rs ->
                if (rs.next()) busyTimeout = rs.getInt(1)
            }
        }
        assertEquals("wal", journalMode.lowercase())
        assertEquals(5000, busyTimeout)
    }

    @Test
    fun `addEntry normalizes fields and stores positive minor units`() {
        val entry =
            service.addEntry(
                AddLedgerEntryRequest(
                    ownerId = "user-1",
                    type = LedgerEntryType.EXPENSE,
                    amount = "150",
                    currency = "twd",
                    category = "  Food ",
                    paymentMethod = " Cash ",
                    note = "  Lunch bento  ",
                ),
            )

        assertTrue(entry.id.isNotBlank(), "Generated entry id should not be blank")
        assertEquals("user-1", entry.ownerId)
        assertEquals(LedgerEntryType.EXPENSE, entry.type)
        assertEquals(150L, entry.amountMinor)
        assertEquals("TWD", entry.currency)
        assertEquals("food", entry.category)
        assertEquals("cash", entry.paymentMethod)
        assertEquals("Lunch bento", entry.note)
        assertEquals(fixedInstant.toEpochMilli(), entry.occurredAt)
        assertEquals(fixedInstant.toEpochMilli(), entry.createdAt)
        assertEquals(fixedInstant.toEpochMilli(), entry.updatedAt)
        assertEquals(null, entry.deletedAt)
    }

    @Test
    fun `addEntry rejects blank owner or blank category`() {
        assertThrows(LedgerValidationException::class.java) {
            service.addEntry(
                AddLedgerEntryRequest(
                    ownerId = "   ",
                    type = LedgerEntryType.EXPENSE,
                    amount = "100",
                    currency = "TWD",
                    category = "food",
                ),
            )
        }
        assertThrows(LedgerValidationException::class.java) {
            service.addEntry(
                AddLedgerEntryRequest(
                    ownerId = "user-1",
                    type = LedgerEntryType.EXPENSE,
                    amount = "100",
                    currency = "TWD",
                    category = "   ",
                ),
            )
        }
    }

    @Test
    fun `listEntries filters by time range, type, category, paymentMethod, keyword and clamps limit and offset`() {
        service.addEntry(
            AddLedgerEntryRequest(
                ownerId = "user-1",
                type = LedgerEntryType.EXPENSE,
                amount = "120",
                currency = "TWD",
                category = "food",
                paymentMethod = "cash",
                note = "Beef noodles",
                occurredAt = 1000L,
            ),
        )
        service.addEntry(
            AddLedgerEntryRequest(
                ownerId = "user-1",
                type = LedgerEntryType.EXPENSE,
                amount = "65",
                currency = "TWD",
                category = "coffee",
                paymentMethod = "line_pay",
                note = "Iced latte",
                occurredAt = 2000L,
            ),
        )
        service.addEntry(
            AddLedgerEntryRequest(
                ownerId = "user-1",
                type = LedgerEntryType.INCOME,
                amount = "50000",
                currency = "TWD",
                category = "salary",
                paymentMethod = "bank",
                note = "Monthly pay",
                occurredAt = 3000L,
            ),
        )

        val rangeFiltered = service.listEntries("user-1", LedgerFilter(from = 1500L, to = 2500L))
        assertEquals(listOf("coffee"), rangeFiltered.map { it.category })

        val typeFiltered = service.listEntries("user-1", LedgerFilter(type = LedgerEntryType.INCOME))
        assertEquals(listOf("salary"), typeFiltered.map { it.category })

        val categoryFiltered = service.listEntries("user-1", LedgerFilter(category = " FOOD "))
        assertEquals(listOf("Beef noodles"), categoryFiltered.map { it.note })

        val paymentFiltered = service.listEntries("user-1", LedgerFilter(paymentMethod = "LINE_PAY"))
        assertEquals(listOf("coffee"), paymentFiltered.map { it.category })

        val keywordFiltered = service.listEntries("user-1", LedgerFilter(keyword = "LATTE"))
        assertEquals(listOf("coffee"), keywordFiltered.map { it.category })

        val clampedLimit = service.listEntries("user-1", LedgerFilter(limit = 0, offset = -5))
        assertEquals(1, clampedLimit.size)
        assertEquals("salary", clampedLimit.first().category)
    }

    @Test
    fun `getSummary groups totals and breakdowns by currency without FX conversion`() {
        service.addEntry(
            AddLedgerEntryRequest(
                ownerId = "user-1",
                type = LedgerEntryType.INCOME,
                amount = "50000",
                currency = "TWD",
                category = "salary",
                paymentMethod = "bank",
                occurredAt = 1000L,
            ),
        )
        service.addEntry(
            AddLedgerEntryRequest(
                ownerId = "user-1",
                type = LedgerEntryType.EXPENSE,
                amount = "150",
                currency = "TWD",
                category = "food",
                paymentMethod = "cash",
                occurredAt = 2000L,
            ),
        )
        service.addEntry(
            AddLedgerEntryRequest(
                ownerId = "user-1",
                type = LedgerEntryType.EXPENSE,
                amount = "12.50",
                currency = "USD",
                category = "software",
                paymentMethod = "credit_card",
                occurredAt = 2500L,
            ),
        )

        val summary = service.getSummary("user-1", from = 500L, to = 3000L)
        assertEquals(2, summary.currencies.size)

        val twd = summary.currencies.first { it.currency == "TWD" }
        assertEquals(50000L, twd.totalIncomeMinor)
        assertEquals(150L, twd.totalExpenseMinor)
        assertEquals(49850L, twd.netMinor)
        assertEquals(2, twd.entryCount)
        assertEquals(listOf("food", "salary"), twd.byCategory.map { it.key }.sorted())
        assertEquals(listOf("bank", "cash"), twd.byPaymentMethod.map { it.key }.sorted())

        val usd = summary.currencies.first { it.currency == "USD" }
        assertEquals(0L, usd.totalIncomeMinor)
        assertEquals(1250L, usd.totalExpenseMinor)
        assertEquals(-1250L, usd.netMinor)
        assertEquals(1, usd.entryCount)
    }

    @Test
    fun `updateEntry updates fields and validates new amount against existing or updated currency`() {
        val created =
            service.addEntry(
                AddLedgerEntryRequest(
                    ownerId = "user-1",
                    type = LedgerEntryType.EXPENSE,
                    amount = "100",
                    currency = "TWD",
                    category = "food",
                    paymentMethod = "cash",
                    note = "Old note",
                    occurredAt = 1000L,
                ),
            )

        val updated =
            service.updateEntry(
                ownerId = "user-1",
                entryId = created.id,
                request =
                    UpdateLedgerEntryRequest(
                        amount = "25.50",
                        currency = "USD",
                        category = "  Dining ",
                        clearPaymentMethod = true,
                        note = "Updated note",
                        occurredAt = 2000L,
                    ),
            )

        assertTrue(updated != null, "Expected updateEntry to return updated entry")
        assertEquals(2550L, updated!!.amountMinor)
        assertEquals("USD", updated.currency)
        assertEquals("dining", updated.category)
        assertEquals(null, updated.paymentMethod)
        assertEquals("Updated note", updated.note)
        assertEquals(2000L, updated.occurredAt)

        assertThrows(LedgerValidationException::class.java) {
            service.updateEntry(
                ownerId = "user-1",
                entryId = created.id,
                request = UpdateLedgerEntryRequest(amount = "10.123"),
            )
        }
    }

    @Test
    fun `listCategories returns distinct non-deleted categories with counts`() {
        service.addEntry(AddLedgerEntryRequest("user-1", LedgerEntryType.EXPENSE, "100", "TWD", "food"))
        service.addEntry(AddLedgerEntryRequest("user-1", LedgerEntryType.EXPENSE, "200", "TWD", "FOOD"))
        service.addEntry(AddLedgerEntryRequest("user-1", LedgerEntryType.EXPENSE, "50", "TWD", "coffee"))

        val categories = service.listCategories("user-1")
        assertEquals(
            listOf(
                LedgerCategoryCount("food", 2),
                LedgerCategoryCount("coffee", 1),
            ),
            categories,
        )
    }

    @Test
    fun `enforces owner isolation and soft-delete exclusion across all operations`() {
        val owner1Entry =
            service.addEntry(
                AddLedgerEntryRequest(
                    ownerId = "owner-1",
                    type = LedgerEntryType.EXPENSE,
                    amount = "300",
                    currency = "TWD",
                    category = "food",
                    paymentMethod = "cash",
                ),
            )
        service.addEntry(
            AddLedgerEntryRequest(
                ownerId = "owner-2",
                type = LedgerEntryType.EXPENSE,
                amount = "500",
                currency = "TWD",
                category = "transport",
            ),
        )

        // Cross-owner isolation
        assertEquals(listOf("transport"), service.listEntries("owner-2").map { it.category })
        assertEquals(listOf(LedgerCategoryCount("transport", 1)), service.listCategories("owner-2"))
        val owner2Summary = service.getSummary("owner-2").currencies.single()
        assertEquals(500L, owner2Summary.totalExpenseMinor)
        assertEquals(null, service.updateEntry("owner-2", owner1Entry.id, UpdateLedgerEntryRequest(note = "hijack")))
        assertEquals(null, service.deleteEntry("owner-2", owner1Entry.id))

        // Soft-delete owner-1's entry
        val deleted = service.deleteEntry("owner-1", owner1Entry.id)
        assertTrue(deleted != null && deleted.deletedAt != null, "Expected soft-deleted entry with deletedAt timestamp")

        // Soft-deleted entry excluded from list, summary, categories, update, and re-delete
        assertTrue(service.listEntries("owner-1").isEmpty(), "Soft-deleted entry must be excluded from listEntries")
        assertTrue(service.getSummary("owner-1").currencies.isEmpty(), "Soft-deleted entry must be excluded from getSummary")
        assertTrue(service.listCategories("owner-1").isEmpty(), "Soft-deleted entry must be excluded from listCategories")
        assertEquals(null, service.updateEntry("owner-1", owner1Entry.id, UpdateLedgerEntryRequest(note = "after delete")))
        assertEquals(null, service.deleteEntry("owner-1", owner1Entry.id))
    }
}
