package com.github._0owoodendooro0.curtly

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object UrlEntries : Table("curtly_urls") {
    val key = varchar("key", 255)
    val longUrl = text("long_url")
    val expiresAt = long("expires_at").nullable()
    val maxClicks = integer("max_clicks").nullable()
    val clickCount = integer("click_count").default(0)

    override val primaryKey = PrimaryKey(key)
}

class ExposedUrlStorage(
    private val database: Database? = null,
    autoCreateTable: Boolean = true
) : UrlStorage {

    init {
        if (autoCreateTable) {
            transaction(db = database) {
                SchemaUtils.create(UrlEntries)
            }
        }
    }

    override fun save(key: String, entry: UrlEntry) {
        transaction(db = database) {
            UrlEntries.upsert {
                it[UrlEntries.key] = key
                it[longUrl] = entry.longUrl
                it[expiresAt] = entry.expiresAt
                it[maxClicks] = entry.maxClicks
                it[clickCount] = entry.clickCount
            }
        }
    }

    override fun get(key: String): UrlEntry? {
        return transaction(db = database) {
            UrlEntries.selectAll()
                .where { UrlEntries.key.eq(key) }
                .map { rowToUrlEntry(it) }
                .singleOrNull()
        }
    }

    override fun incrementClick(key: String): Boolean {
        return transaction(db = database) {
            val updatedRows = UrlEntries.update({ UrlEntries.key.eq(key) }) {
                it.update(UrlEntries.clickCount, UrlEntries.clickCount.plus(1))
            }
            updatedRows > 0
        }
    }

    override fun getAll(): Map<String, UrlEntry> {
        return transaction(db = database) {
            UrlEntries.selectAll().associate { row ->
                row[UrlEntries.key] to rowToUrlEntry(row)
            }
        }
    }

    private fun rowToUrlEntry(row: ResultRow): UrlEntry {
        return UrlEntry(
            longUrl = row[UrlEntries.longUrl],
            expiresAt = row[UrlEntries.expiresAt],
            maxClicks = row[UrlEntries.maxClicks],
            clickCount = row[UrlEntries.clickCount]
        )
    }
}
