package luna.ledger

import java.time.Clock
import java.util.UUID

class LedgerService(
    private val repository: LedgerRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun addEntry(request: AddLedgerEntryRequest): LedgerEntry {
        val ownerId = normalizeRequired(request.ownerId, "Owner ID")
        val currency = LedgerMoney.normalizeCurrency(request.currency)
        val amountMinor = LedgerMoney.parseToMinorUnits(request.amount, currency)
        val category = normalizeCategory(request.category)
        val paymentMethod = normalizeOptionalLowercase(request.paymentMethod)
        val note = normalizeOptionalText(request.note)
        val now = clock.millis()
        val occurredAt = request.occurredAt ?: now

        val entry =
            LedgerEntry(
                id = UUID.randomUUID().toString(),
                ownerId = ownerId,
                type = request.type,
                amountMinor = amountMinor,
                currency = currency,
                category = category,
                paymentMethod = paymentMethod,
                note = note,
                occurredAt = occurredAt,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
        return repository.insert(entry)
    }

    fun listEntries(
        ownerId: String,
        filter: LedgerFilter = LedgerFilter(),
    ): List<LedgerEntry> {
        val normalizedOwnerId = normalizeRequired(ownerId, "Owner ID")
        val normalizedFilter =
            filter.copy(
                category = filter.category?.let { normalizeOptionalLowercase(it) },
                paymentMethod = filter.paymentMethod?.let { normalizeOptionalLowercase(it) },
                keyword = filter.keyword?.let { normalizeOptionalText(it) },
                limit = filter.limit.coerceIn(1, 100),
                offset = filter.offset.coerceAtLeast(0),
            )
        return repository.list(normalizedOwnerId, normalizedFilter)
    }

    fun getSummary(
        ownerId: String,
        from: Long? = null,
        to: Long? = null,
    ): LedgerSummary {
        val normalizedOwnerId = normalizeRequired(ownerId, "Owner ID")
        val entries = repository.listForRange(normalizedOwnerId, from, to)
        val currencies =
            entries
                .groupBy { it.currency }
                .toSortedMap()
                .map { (currency, currencyEntries) ->
                    buildCurrencySummary(currency, currencyEntries)
                }
        return LedgerSummary(from = from, to = to, currencies = currencies)
    }

    fun updateEntry(
        ownerId: String,
        entryId: String,
        request: UpdateLedgerEntryRequest,
    ): LedgerEntry? {
        val normalizedOwnerId = normalizeRequired(ownerId, "Owner ID")
        val normalizedEntryId = normalizeRequired(entryId, "Entry ID")
        val existing = repository.findById(normalizedOwnerId, normalizedEntryId) ?: return null

        val nextCurrency = request.currency?.let { LedgerMoney.normalizeCurrency(it) } ?: existing.currency
        val nextAmountMinor =
            request.amount?.let { LedgerMoney.parseToMinorUnits(it, nextCurrency) }
                ?: existing.amountMinor
        val nextCategory = request.category?.let { normalizeCategory(it) } ?: existing.category
        val nextPaymentMethod =
            when {
                request.clearPaymentMethod -> null
                request.paymentMethod != null -> normalizeOptionalLowercase(request.paymentMethod)
                else -> existing.paymentMethod
            }
        val nextNote =
            when {
                request.clearNote -> null
                request.note != null -> normalizeOptionalText(request.note)
                else -> existing.note
            }
        val now = clock.millis()

        val updated =
            existing.copy(
                type = request.type ?: existing.type,
                amountMinor = nextAmountMinor,
                currency = nextCurrency,
                category = nextCategory,
                paymentMethod = nextPaymentMethod,
                note = nextNote,
                occurredAt = request.occurredAt ?: existing.occurredAt,
                updatedAt = now,
            )
        return repository.update(normalizedOwnerId, normalizedEntryId, updated)
    }

    fun deleteEntry(
        ownerId: String,
        entryId: String,
    ): LedgerEntry? {
        val normalizedOwnerId = normalizeRequired(ownerId, "Owner ID")
        val normalizedEntryId = normalizeRequired(entryId, "Entry ID")
        val now = clock.millis()
        return repository.softDelete(normalizedOwnerId, normalizedEntryId, deletedAt = now, updatedAt = now)
    }

    fun listCategories(ownerId: String): List<LedgerCategoryCount> {
        val normalizedOwnerId = normalizeRequired(ownerId, "Owner ID")
        return repository.listCategories(normalizedOwnerId)
    }

    private fun buildCurrencySummary(
        currency: String,
        entries: List<LedgerEntry>,
    ): LedgerCurrencySummary {
        var totalIncome = 0L
        var totalExpense = 0L
        for (entry in entries) {
            when (entry.type) {
                LedgerEntryType.INCOME -> totalIncome += entry.amountMinor
                LedgerEntryType.EXPENSE -> totalExpense += entry.amountMinor
            }
        }
        return LedgerCurrencySummary(
            currency = currency,
            totalIncomeMinor = totalIncome,
            totalExpenseMinor = totalExpense,
            netMinor = totalIncome - totalExpense,
            entryCount = entries.size,
            byCategory = aggregateBreakdown(entries) { it.category },
            byPaymentMethod = aggregateBreakdown(entries) { it.paymentMethod ?: "unspecified" },
        )
    }

    private fun aggregateBreakdown(
        entries: List<LedgerEntry>,
        keySelector: (LedgerEntry) -> String,
    ): List<LedgerBreakdown> =
        entries
            .groupBy(keySelector)
            .map { (key, group) ->
                var income = 0L
                var expense = 0L
                for (entry in group) {
                    when (entry.type) {
                        LedgerEntryType.INCOME -> income += entry.amountMinor
                        LedgerEntryType.EXPENSE -> expense += entry.amountMinor
                    }
                }
                LedgerBreakdown(key = key, incomeMinor = income, expenseMinor = expense, count = group.size)
            }.sortedWith(compareByDescending<LedgerBreakdown> { it.incomeMinor + it.expenseMinor }.thenBy { it.key })

    private fun normalizeRequired(
        raw: String,
        fieldName: String,
    ): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw LedgerValidationException("$fieldName must not be blank")
        }
        return trimmed
    }

    private fun normalizeCategory(raw: String): String {
        val normalized = raw.trim().lowercase()
        if (normalized.isEmpty()) {
            throw LedgerValidationException("Category must not be blank")
        }
        return normalized
    }

    private fun normalizeOptionalLowercase(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun normalizeOptionalText(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
