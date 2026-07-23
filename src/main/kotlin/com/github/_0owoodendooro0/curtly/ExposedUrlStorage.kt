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
    val ownerId = varchar("owner_id", 255).nullable()

    override val primaryKey = PrimaryKey(key)
}

object ClickLogsEntries : Table("curtly_click_logs") {
    val id = varchar("id", 36)
    val key = varchar("key", 255)
    val timestamp = long("timestamp")
    val ip = varchar("ip", 45).nullable()
    val userAgent = text("user_agent").nullable()
    val referer = text("referer").nullable()

    override val primaryKey = PrimaryKey(id)
}

class ExposedUrlStorage(
    private val database: Database? = null,
    autoCreateTable: Boolean = true
) : UrlStorage {

    init {
        if (autoCreateTable) {
            transaction(db = database) {
                SchemaUtils.create(UrlEntries, ClickLogsEntries)
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
                it[ownerId] = entry.ownerId
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

    override fun getByOwnerId(ownerId: String): Map<String, UrlEntry> {
        return transaction(db = database) {
            UrlEntries.selectAll()
                .where { UrlEntries.ownerId.eq(ownerId) }
                .associate { row ->
                    row[UrlEntries.key] to rowToUrlEntry(row)
                }
        }
    }

    override fun countByOwnerId(ownerId: String): Int {
        return transaction(db = database) {
            UrlEntries.selectAll()
                .where { UrlEntries.ownerId.eq(ownerId) }
                .count()
                .toInt()
        }
    }

    override fun remove(key: String): Boolean {
        return transaction(db = database) {
            ClickLogsEntries.deleteWhere { ClickLogsEntries.key.eq(key) }
            UrlEntries.deleteWhere { UrlEntries.key.eq(key) } > 0
        }
    }

    override fun removeByOwnerId(ownerId: String): Int {
        return transaction(db = database) {
            val keys = UrlEntries.selectAll()
                .where { UrlEntries.ownerId.eq(ownerId) }
                .map { it[UrlEntries.key] }
            if (keys.isNotEmpty()) {
                ClickLogsEntries.deleteWhere { ClickLogsEntries.key.inList(keys) }
            }
            UrlEntries.deleteWhere { UrlEntries.ownerId.eq(ownerId) }
        }
    }

    override fun recordClickLog(log: ClickLog) {
        transaction(db = database) {
            ClickLogsEntries.insert {
                it[id] = log.id
                it[key] = log.key
                it[timestamp] = log.timestamp
                it[ip] = log.ip
                it[userAgent] = log.userAgent
                it[referer] = log.referer
            }
        }
    }

    override fun getClickLogs(key: String): List<ClickLog> {
        return transaction(db = database) {
            ClickLogsEntries.selectAll()
                .where { ClickLogsEntries.key.eq(key) }
                .map { rowToClickLog(it) }
        }
    }

    override fun getAllClickLogs(): List<ClickLog> {
        return transaction(db = database) {
            ClickLogsEntries.selectAll()
                .map { rowToClickLog(it) }
        }
    }

    override fun clearClickLogs(key: String): Boolean {
        return transaction(db = database) {
            ClickLogsEntries.deleteWhere { ClickLogsEntries.key.eq(key) } > 0
        }
    }

    private fun rowToUrlEntry(row: ResultRow): UrlEntry {
        return UrlEntry(
            longUrl = row[UrlEntries.longUrl],
            expiresAt = row[UrlEntries.expiresAt],
            maxClicks = row[UrlEntries.maxClicks],
            clickCount = row[UrlEntries.clickCount],
            ownerId = row[UrlEntries.ownerId]
        )
    }

    private fun rowToClickLog(row: ResultRow): ClickLog {
        return ClickLog(
            id = row[ClickLogsEntries.id],
            key = row[ClickLogsEntries.key],
            timestamp = row[ClickLogsEntries.timestamp],
            ip = row[ClickLogsEntries.ip],
            userAgent = row[ClickLogsEntries.userAgent],
            referer = row[ClickLogsEntries.referer]
        )
    }
}

