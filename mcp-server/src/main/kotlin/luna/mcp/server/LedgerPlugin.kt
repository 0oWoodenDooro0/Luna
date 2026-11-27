package luna.mcp.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import luna.ledger.AddLedgerEntryRequest
import luna.ledger.LedgerBreakdown
import luna.ledger.LedgerCurrencySummary
import luna.ledger.LedgerEntry
import luna.ledger.LedgerEntryType
import luna.ledger.LedgerFilter
import luna.ledger.LedgerMoney
import luna.ledger.LedgerService
import luna.ledger.LedgerSummary
import luna.ledger.LedgerValidationException
import luna.ledger.UpdateLedgerEntryRequest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Built-in [McpServerPlugin] exposing personal ledger tools backed by [LedgerService].
 */
class LedgerPlugin(
    private val ledgerService: LedgerService,
    private val defaultCurrency: String = McpServerConfig.DEFAULT_LEDGER_DEFAULT_CURRENCY,
    private val timezone: ZoneId = ZoneId.of(McpServerConfig.DEFAULT_LEDGER_TIMEZONE),
    private val allowedUserIds: Set<String> = emptySet(),
) : McpServerPlugin {
    override val name: String = "ledger"

    override fun isEnabledFor(auth: McpAuthContext): Boolean {
        val ownerId = auth.ownerId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return allowedUserIds.isEmpty() || ownerId in allowedUserIds
    }

    private val addEntryTool =
        createTool(
            name = TOOL_ADD_ENTRY,
            description = "Record a new personal income or expense entry in the ledger.",
            schema = addEntrySchema(),
        ) { arguments, ownerId -> executeAddEntry(arguments, ownerId) }

    private val listEntriesTool =
        createTool(
            name = TOOL_LIST_ENTRIES,
            description = "List and filter personal ledger entries by date range, type, category, payment method, or keyword.",
            schema = listEntriesSchema(),
        ) { arguments, ownerId -> executeListEntries(arguments, ownerId) }

    private val getSummaryTool =
        createTool(
            name = TOOL_GET_SUMMARY,
            description = "Get multi-currency income, expense, net totals, and category/payment-method breakdowns.",
            schema = getSummarySchema(),
        ) { arguments, ownerId -> executeGetSummary(arguments, ownerId) }

    private val updateEntryTool =
        createTool(
            name = TOOL_UPDATE_ENTRY,
            description = "Update an existing personal ledger entry owned by the authenticated user.",
            schema = updateEntrySchema(),
        ) { arguments, ownerId -> executeUpdateEntry(arguments, ownerId) }

    private val deleteEntryTool =
        createTool(
            name = TOOL_DELETE_ENTRY,
            description = "Soft-delete an existing personal ledger entry owned by the authenticated user.",
            schema = deleteEntrySchema(),
        ) { arguments, ownerId -> executeDeleteEntry(arguments, ownerId) }

    private val listCategoriesTool =
        createTool(
            name = TOOL_LIST_CATEGORIES,
            description = "List distinct ledger categories and entry counts for the authenticated user.",
            schema =
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                },
        ) { _, ownerId -> executeListCategories(ownerId) }

    override fun tools(): List<McpTool> =
        listOf(
            addEntryTool,
            listEntriesTool,
            getSummaryTool,
            updateEntryTool,
            deleteEntryTool,
            listCategoriesTool,
        )

    private fun createTool(
        name: String,
        description: String,
        schema: JsonObject,
        block: (JsonObject?, String) -> McpToolResult,
    ): McpTool =
        object : McpTool {
            override val name: String = name
            override val description: String = description
            override val inputSchema: JsonObject = schema

            override suspend fun execute(
                arguments: JsonObject?,
                auth: McpAuthContext,
            ): McpToolResult {
                if (!isEnabledFor(auth)) {
                    return McpToolResult.Text(
                        text = "Unauthorized: ledger access is not permitted for this caller",
                        isError = true,
                    )
                }
                val ownerId = auth.ownerId!!.trim()
                return try {
                    block(arguments, ownerId)
                } catch (e: LedgerValidationException) {
                    McpToolResult.Text(text = e.message ?: "Invalid ledger arguments", isError = true)
                }
            }
        }

    private fun executeAddEntry(
        arguments: JsonObject?,
        ownerId: String,
    ): McpToolResult {
        val rawType = requireStringArg(arguments, "type")
        val rawAmount = requireStringArg(arguments, "amount")
        val category = requireStringArg(arguments, "category")
        val currency = optionalStringArg(arguments, "currency") ?: defaultCurrency
        val paymentMethod = optionalStringArg(arguments, "payment_method")
        val note = optionalStringArg(arguments, "note")
        val occurredAt = optionalStringArg(arguments, "occurred_at")?.let { parseTimestamp(it, isRangeEnd = false) }

        val entry =
            ledgerService.addEntry(
                AddLedgerEntryRequest(
                    ownerId = ownerId,
                    type = LedgerEntryType.parse(rawType),
                    amount = rawAmount,
                    currency = currency,
                    category = category,
                    paymentMethod = paymentMethod,
                    note = note,
                    occurredAt = occurredAt,
                ),
            )
        return McpToolResult.Text(text = entryToJson(entry).toString(), isError = false)
    }

    private fun executeListEntries(
        arguments: JsonObject?,
        ownerId: String,
    ): McpToolResult {
        val from = optionalStringArg(arguments, "from")?.let { parseTimestamp(it, isRangeEnd = false) }
        val to = optionalStringArg(arguments, "to")?.let { parseTimestamp(it, isRangeEnd = true) }
        validateDateRange(from, to)

        val type = optionalStringArg(arguments, "type")?.let { LedgerEntryType.parse(it) }
        val category = optionalStringArg(arguments, "category")
        val paymentMethod = optionalStringArg(arguments, "payment_method")
        val keyword = optionalStringArg(arguments, "keyword")
        val limit = optionalIntArg(arguments, "limit") ?: DEFAULT_LIMIT
        val offset = optionalIntArg(arguments, "offset") ?: 0

        val entries =
            ledgerService.listEntries(
                ownerId = ownerId,
                filter =
                    LedgerFilter(
                        from = from,
                        to = to,
                        type = type,
                        category = category,
                        paymentMethod = paymentMethod,
                        keyword = keyword,
                        limit = limit,
                        offset = offset,
                    ),
            )
        val payload =
            buildJsonObject {
                put("count", entries.size)
                put("timezone", timezone.id)
                putJsonArray("entries") {
                    entries.forEach { add(entryToJson(it)) }
                }
            }
        return McpToolResult.Text(text = payload.toString(), isError = false)
    }

    private fun executeGetSummary(
        arguments: JsonObject?,
        ownerId: String,
    ): McpToolResult {
        val from = optionalStringArg(arguments, "from")?.let { parseTimestamp(it, isRangeEnd = false) }
        val to = optionalStringArg(arguments, "to")?.let { parseTimestamp(it, isRangeEnd = true) }
        validateDateRange(from, to)

        val summary = ledgerService.getSummary(ownerId = ownerId, from = from, to = to)
        return McpToolResult.Text(text = summaryToJson(summary).toString(), isError = false)
    }

    private fun executeUpdateEntry(
        arguments: JsonObject?,
        ownerId: String,
    ): McpToolResult {
        val entryId = requireStringArg(arguments, "entry_id")
        val type = optionalStringArg(arguments, "type")?.let { LedgerEntryType.parse(it) }
        val amount = optionalStringArg(arguments, "amount")
        val currency = optionalStringArg(arguments, "currency")
        val category = optionalStringArg(arguments, "category")
        val paymentMethod = optionalStringArg(arguments, "payment_method")
        val clearPaymentMethod = optionalBooleanArg(arguments, "clear_payment_method") ?: false
        val note = optionalStringArg(arguments, "note")
        val clearNote = optionalBooleanArg(arguments, "clear_note") ?: false
        val occurredAt = optionalStringArg(arguments, "occurred_at")?.let { parseTimestamp(it, isRangeEnd = false) }

        val updated =
            ledgerService.updateEntry(
                ownerId = ownerId,
                entryId = entryId,
                request =
                    UpdateLedgerEntryRequest(
                        type = type,
                        amount = amount,
                        currency = currency,
                        category = category,
                        paymentMethod = paymentMethod,
                        clearPaymentMethod = clearPaymentMethod,
                        note = note,
                        clearNote = clearNote,
                        occurredAt = occurredAt,
                    ),
            )
                ?: return McpToolResult.Text(text = "Ledger entry not found: $entryId", isError = true)

        return McpToolResult.Text(text = entryToJson(updated).toString(), isError = false)
    }

    private fun executeDeleteEntry(
        arguments: JsonObject?,
        ownerId: String,
    ): McpToolResult {
        val entryId = requireStringArg(arguments, "entry_id")
        val deleted =
            ledgerService.deleteEntry(ownerId = ownerId, entryId = entryId)
                ?: return McpToolResult.Text(text = "Ledger entry not found: $entryId", isError = true)

        val payload =
            buildJsonObject {
                put("deleted", true)
                put("entry", entryToJson(deleted))
            }
        return McpToolResult.Text(text = payload.toString(), isError = false)
    }

    private fun executeListCategories(ownerId: String): McpToolResult {
        val categories = ledgerService.listCategories(ownerId)
        val payload =
            buildJsonObject {
                putJsonArray("categories") {
                    for (item in categories) {
                        add(
                            buildJsonObject {
                                put("category", item.category)
                                put("count", item.count)
                            },
                        )
                    }
                }
            }
        return McpToolResult.Text(text = payload.toString(), isError = false)
    }

    private fun validateDateRange(
        from: Long?,
        to: Long?,
    ) {
        if (from != null && to != null && from > to) {
            throw LedgerValidationException("Invalid date range: 'from' must be before or equal to 'to'")
        }
    }

    private fun parseTimestamp(
        raw: String,
        isRangeEnd: Boolean,
    ): Long {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw LedgerValidationException("Timestamp must not be blank")
        }
        return tryParseLocalDate(trimmed, isRangeEnd)
            ?: tryParseOffsetOrInstant(trimmed)
            ?: tryParseLocalDateTime(trimmed)
            ?: throw LedgerValidationException(
                "Invalid date/timestamp '$raw': expected YYYY-MM-DD or ISO-8601 datetime",
            )
    }

    private fun tryParseLocalDate(
        raw: String,
        isRangeEnd: Boolean,
    ): Long? =
        try {
            val date = LocalDate.parse(raw)
            if (isRangeEnd) {
                date
                    .plusDays(1)
                    .atStartOfDay(timezone)
                    .toInstant()
                    .toEpochMilli() - 1L
            } else {
                date.atStartOfDay(timezone).toInstant().toEpochMilli()
            }
        } catch (_: DateTimeParseException) {
            null
        }

    private fun tryParseOffsetOrInstant(raw: String): Long? =
        try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                Instant.parse(raw).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }

    private fun tryParseLocalDateTime(raw: String): Long? =
        try {
            LocalDateTime
                .parse(raw)
                .atZone(timezone)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

    private fun formatEpoch(epochMs: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(epochMs).atZone(timezone),
        )

    private fun entryToJson(entry: LedgerEntry): JsonObject =
        buildJsonObject {
            put("id", entry.id)
            put("ownerId", entry.ownerId)
            put("type", entry.type.name)
            put("amount", LedgerMoney.formatFromMinorUnits(entry.amountMinor, entry.currency))
            put("amountMinor", entry.amountMinor)
            put("currency", entry.currency)
            put("category", entry.category)
            put("paymentMethod", entry.paymentMethod)
            put("note", entry.note)
            put("occurredAt", formatEpoch(entry.occurredAt))
            put("occurredAtEpochMs", entry.occurredAt)
            put("createdAt", formatEpoch(entry.createdAt))
            put("updatedAt", formatEpoch(entry.updatedAt))
        }

    private fun summaryToJson(summary: LedgerSummary): JsonObject =
        buildJsonObject {
            put("timezone", timezone.id)
            put("from", summary.from?.let(::formatEpoch))
            put("to", summary.to?.let(::formatEpoch))
            putJsonArray("currencies") {
                summary.currencies.forEach { add(currencySummaryToJson(it)) }
            }
        }

    private fun currencySummaryToJson(summary: LedgerCurrencySummary): JsonObject =
        buildJsonObject {
            put("currency", summary.currency)
            put("totalIncome", LedgerMoney.formatFromMinorUnits(summary.totalIncomeMinor, summary.currency))
            put("totalIncomeMinor", summary.totalIncomeMinor)
            put("totalExpense", LedgerMoney.formatFromMinorUnits(summary.totalExpenseMinor, summary.currency))
            put("totalExpenseMinor", summary.totalExpenseMinor)
            put("net", LedgerMoney.formatFromMinorUnits(summary.netMinor, summary.currency))
            put("netMinor", summary.netMinor)
            put("entryCount", summary.entryCount)
            put("byCategory", breakdownsToJson(summary.byCategory, summary.currency))
            put("byPaymentMethod", breakdownsToJson(summary.byPaymentMethod, summary.currency))
        }

    private fun breakdownsToJson(
        items: List<LedgerBreakdown>,
        currency: String,
    ): JsonArray =
        kotlinx.serialization.json.buildJsonArray {
            for (item in items) {
                add(
                    buildJsonObject {
                        put("key", item.key)
                        put("income", LedgerMoney.formatFromMinorUnits(item.incomeMinor, currency))
                        put("incomeMinor", item.incomeMinor)
                        put("expense", LedgerMoney.formatFromMinorUnits(item.expenseMinor, currency))
                        put("expenseMinor", item.expenseMinor)
                        put("count", item.count)
                    },
                )
            }
        }

    private fun requireStringArg(
        arguments: JsonObject?,
        key: String,
    ): String =
        optionalStringArg(arguments, key)
            ?: throw LedgerValidationException("Missing or blank required argument '$key'")

    private fun optionalStringArg(
        arguments: JsonObject?,
        key: String,
    ): String? =
        arguments
            ?.get(key)
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun optionalIntArg(
        arguments: JsonObject?,
        key: String,
    ): Int? {
        val element = arguments?.get(key) ?: return null
        val primitive = element.jsonPrimitive
        return primitive.intOrNull
            ?: throw LedgerValidationException("Argument '$key' must be an integer")
    }

    private fun optionalBooleanArg(
        arguments: JsonObject?,
        key: String,
    ): Boolean? {
        val element = arguments?.get(key) ?: return null
        val primitive = element.jsonPrimitive
        return primitive.booleanOrNull
            ?: throw LedgerValidationException("Argument '$key' must be a boolean")
    }

    private fun addEntrySchema(): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("type") {
                    put("type", "string")
                    putJsonArray("enum") {
                        add(JsonPrimitive("INCOME"))
                        add(JsonPrimitive("EXPENSE"))
                    }
                }
                putJsonObject("amount") {
                    put("type", "string")
                    put("description", "Positive decimal amount in major units (e.g. '150' or '12.50')")
                }
                putJsonObject("currency") {
                    put("type", "string")
                    put("description", "ISO-4217 currency code (defaults to $defaultCurrency)")
                }
                putJsonObject("category") { put("type", "string") }
                putJsonObject("payment_method") { put("type", "string") }
                putJsonObject("note") { put("type", "string") }
                putJsonObject("occurred_at") {
                    put("type", "string")
                    put("description", "ISO-8601 date or datetime (defaults to current time)")
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("type"))
                add(JsonPrimitive("amount"))
                add(JsonPrimitive("category"))
            }
        }

    private fun listEntriesSchema(): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("from") { put("type", "string") }
                putJsonObject("to") { put("type", "string") }
                putJsonObject("type") { put("type", "string") }
                putJsonObject("category") { put("type", "string") }
                putJsonObject("payment_method") { put("type", "string") }
                putJsonObject("keyword") { put("type", "string") }
                putJsonObject("limit") { put("type", "integer") }
                putJsonObject("offset") { put("type", "integer") }
            }
        }

    private fun getSummarySchema(): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("from") { put("type", "string") }
                putJsonObject("to") { put("type", "string") }
            }
        }

    private fun updateEntrySchema(): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("entry_id") { put("type", "string") }
                putJsonObject("type") { put("type", "string") }
                putJsonObject("amount") { put("type", "string") }
                putJsonObject("currency") { put("type", "string") }
                putJsonObject("category") { put("type", "string") }
                putJsonObject("payment_method") { put("type", "string") }
                putJsonObject("clear_payment_method") { put("type", "boolean") }
                putJsonObject("note") { put("type", "string") }
                putJsonObject("clear_note") { put("type", "boolean") }
                putJsonObject("occurred_at") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("entry_id")) }
        }

    private fun deleteEntrySchema(): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("entry_id") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("entry_id")) }
        }

    companion object {
        const val TOOL_ADD_ENTRY = "ledger_add_entry"
        const val TOOL_LIST_ENTRIES = "ledger_list_entries"
        const val TOOL_GET_SUMMARY = "ledger_get_summary"
        const val TOOL_UPDATE_ENTRY = "ledger_update_entry"
        const val TOOL_DELETE_ENTRY = "ledger_delete_entry"
        const val TOOL_LIST_CATEGORIES = "ledger_list_categories"
        private const val DEFAULT_LIMIT = 50
    }
}
