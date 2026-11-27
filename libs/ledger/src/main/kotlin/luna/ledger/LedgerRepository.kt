package luna.ledger

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class LedgerRepository(
    private val database: Database,
) {
    fun insert(entry: LedgerEntry): LedgerEntry {
        transaction(database) {
            LedgerEntriesTable.insert {
                it[id] = entry.id
                it[ownerId] = entry.ownerId
                it[type] = entry.type.name
                it[amountMinor] = entry.amountMinor
                it[currency] = entry.currency
                it[category] = entry.category
                it[paymentMethod] = entry.paymentMethod
                it[note] = entry.note
                it[occurredAt] = entry.occurredAt
                it[createdAt] = entry.createdAt
                it[updatedAt] = entry.updatedAt
                it[deletedAt] = entry.deletedAt
            }
        }
        return entry
    }

    fun list(
        ownerId: String,
        filter: LedgerFilter,
    ): List<LedgerEntry> {
        val clampedLimit = filter.limit.coerceIn(1, 100)
        val clampedOffset = filter.offset.coerceAtLeast(0).toLong()
        return transaction(database) {
            LedgerEntriesTable
                .selectAll()
                .where { buildFilterCondition(ownerId, filter) }
                .orderBy(LedgerEntriesTable.occurredAt to SortOrder.DESC, LedgerEntriesTable.createdAt to SortOrder.DESC)
                .limit(clampedLimit)
                .offset(clampedOffset)
                .map { it.toLedgerEntry() }
        }
    }

    fun findById(
        ownerId: String,
        entryId: String,
    ): LedgerEntry? =
        transaction(database) {
            LedgerEntriesTable
                .selectAll()
                .where {
                    (LedgerEntriesTable.id eq entryId) and
                        (LedgerEntriesTable.ownerId eq ownerId) and
                        LedgerEntriesTable.deletedAt.isNull()
                }.singleOrNull()
                ?.toLedgerEntry()
        }

    fun listForRange(
        ownerId: String,
        from: Long?,
        to: Long?,
    ): List<LedgerEntry> =
        transaction(database) {
            var condition: Op<Boolean> =
                (LedgerEntriesTable.ownerId eq ownerId) and LedgerEntriesTable.deletedAt.isNull()
            from?.let { condition = condition and (LedgerEntriesTable.occurredAt greaterEq it) }
            to?.let { condition = condition and (LedgerEntriesTable.occurredAt lessEq it) }
            LedgerEntriesTable
                .selectAll()
                .where { condition }
                .map { it.toLedgerEntry() }
        }

    fun update(
        ownerId: String,
        entryId: String,
        updated: LedgerEntry,
    ): LedgerEntry? {
        val count =
            transaction(database) {
                LedgerEntriesTable.update(
                    where = {
                        (LedgerEntriesTable.id eq entryId) and
                            (LedgerEntriesTable.ownerId eq ownerId) and
                            LedgerEntriesTable.deletedAt.isNull()
                    },
                ) {
                    it[type] = updated.type.name
                    it[amountMinor] = updated.amountMinor
                    it[currency] = updated.currency
                    it[category] = updated.category
                    it[paymentMethod] = updated.paymentMethod
                    it[note] = updated.note
                    it[occurredAt] = updated.occurredAt
                    it[updatedAt] = updated.updatedAt
                }
            }
        return if (count > 0) updated else null
    }

    fun softDelete(
        ownerId: String,
        entryId: String,
        deletedAt: Long,
        updatedAt: Long,
    ): LedgerEntry? =
        transaction(database) {
            val count =
                LedgerEntriesTable.update(
                    where = {
                        (LedgerEntriesTable.id eq entryId) and
                            (LedgerEntriesTable.ownerId eq ownerId) and
                            LedgerEntriesTable.deletedAt.isNull()
                    },
                ) {
                    it[LedgerEntriesTable.deletedAt] = deletedAt
                    it[LedgerEntriesTable.updatedAt] = updatedAt
                }
            if (count == 0) {
                null
            } else {
                LedgerEntriesTable
                    .selectAll()
                    .where {
                        (LedgerEntriesTable.id eq entryId) and
                            (LedgerEntriesTable.ownerId eq ownerId)
                    }.singleOrNull()
                    ?.toLedgerEntry()
            }
        }

    fun listCategories(ownerId: String): List<LedgerCategoryCount> {
        val countExpr = LedgerEntriesTable.id.count()
        return transaction(database) {
            LedgerEntriesTable
                .select(LedgerEntriesTable.category, countExpr)
                .where {
                    (LedgerEntriesTable.ownerId eq ownerId) and LedgerEntriesTable.deletedAt.isNull()
                }.groupBy(LedgerEntriesTable.category)
                .orderBy(countExpr to SortOrder.DESC, LedgerEntriesTable.category to SortOrder.ASC)
                .map { row ->
                    LedgerCategoryCount(
                        category = row[LedgerEntriesTable.category],
                        count = row[countExpr].toInt(),
                    )
                }
        }
    }

    private fun buildFilterCondition(
        ownerId: String,
        filter: LedgerFilter,
    ): Op<Boolean> {
        var condition: Op<Boolean> =
            (LedgerEntriesTable.ownerId eq ownerId) and LedgerEntriesTable.deletedAt.isNull()

        filter.from?.let { from ->
            condition = condition and (LedgerEntriesTable.occurredAt greaterEq from)
        }
        filter.to?.let { to ->
            condition = condition and (LedgerEntriesTable.occurredAt lessEq to)
        }
        filter.type?.let { type ->
            condition = condition and (LedgerEntriesTable.type eq type.name)
        }
        filter.category?.let { category ->
            condition = condition and (LedgerEntriesTable.category eq category)
        }
        filter.paymentMethod?.let { paymentMethod ->
            condition = condition and (LedgerEntriesTable.paymentMethod eq paymentMethod)
        }
        filter.keyword?.let { keyword ->
            val pattern = "%${escapeLike(keyword.lowercase())}%"
            condition =
                condition and (
                    (LedgerEntriesTable.category.lowerCase() like pattern) or
                        (LedgerEntriesTable.note.lowerCase() like pattern) or
                        (LedgerEntriesTable.paymentMethod.lowerCase() like pattern)
                )
        }
        return condition
    }

    private fun escapeLike(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun ResultRow.toLedgerEntry(): LedgerEntry =
        LedgerEntry(
            id = this[LedgerEntriesTable.id],
            ownerId = this[LedgerEntriesTable.ownerId],
            type = LedgerEntryType.parse(this[LedgerEntriesTable.type]),
            amountMinor = this[LedgerEntriesTable.amountMinor],
            currency = this[LedgerEntriesTable.currency],
            category = this[LedgerEntriesTable.category],
            paymentMethod = this[LedgerEntriesTable.paymentMethod],
            note = this[LedgerEntriesTable.note],
            occurredAt = this[LedgerEntriesTable.occurredAt],
            createdAt = this[LedgerEntriesTable.createdAt],
            updatedAt = this[LedgerEntriesTable.updatedAt],
            deletedAt = this[LedgerEntriesTable.deletedAt],
        )
}
