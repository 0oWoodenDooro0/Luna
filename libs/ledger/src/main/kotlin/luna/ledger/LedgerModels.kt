package luna.ledger

enum class LedgerEntryType {
    INCOME,
    EXPENSE,
    ;

    companion object {
        fun parse(raw: String): LedgerEntryType {
            val normalized = raw.trim().uppercase()
            return entries.firstOrNull { it.name == normalized }
                ?: throw LedgerValidationException("Invalid entry type '$raw': expected INCOME or EXPENSE")
        }
    }
}

class LedgerValidationException(
    message: String,
) : IllegalArgumentException(message)

data class LedgerEntry(
    val id: String,
    val ownerId: String,
    val type: LedgerEntryType,
    val amountMinor: Long,
    val currency: String,
    val category: String,
    val paymentMethod: String?,
    val note: String?,
    val occurredAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

data class AddLedgerEntryRequest(
    val ownerId: String,
    val type: LedgerEntryType,
    val amount: String,
    val currency: String = "TWD",
    val category: String,
    val paymentMethod: String? = null,
    val note: String? = null,
    val occurredAt: Long? = null,
)

data class LedgerFilter(
    val from: Long? = null,
    val to: Long? = null,
    val type: LedgerEntryType? = null,
    val category: String? = null,
    val paymentMethod: String? = null,
    val keyword: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
)

data class UpdateLedgerEntryRequest(
    val type: LedgerEntryType? = null,
    val amount: String? = null,
    val currency: String? = null,
    val category: String? = null,
    val paymentMethod: String? = null,
    val clearPaymentMethod: Boolean = false,
    val note: String? = null,
    val clearNote: Boolean = false,
    val occurredAt: Long? = null,
)

data class LedgerBreakdown(
    val key: String,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val count: Int,
)

data class LedgerCurrencySummary(
    val currency: String,
    val totalIncomeMinor: Long,
    val totalExpenseMinor: Long,
    val netMinor: Long,
    val entryCount: Int,
    val byCategory: List<LedgerBreakdown>,
    val byPaymentMethod: List<LedgerBreakdown>,
)

data class LedgerSummary(
    val from: Long?,
    val to: Long?,
    val currencies: List<LedgerCurrencySummary>,
)

data class LedgerCategoryCount(
    val category: String,
    val count: Int,
)
