package luna.ledger

import org.jetbrains.exposed.v1.core.Table

internal object LedgerEntriesTable : Table("ledger_entries") {
    val id = varchar("id", 64)
    val ownerId = varchar("owner_id", 64).index()
    val type = varchar("type", 16)
    val amountMinor = long("amount_minor")
    val currency = varchar("currency", 8)
    val category = varchar("category", 64)
    val paymentMethod = varchar("payment_method", 64).nullable()
    val note = text("note").nullable()
    val occurredAt = long("occurred_at")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val deletedAt = long("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, ownerId, deletedAt, occurredAt)
    }
}
