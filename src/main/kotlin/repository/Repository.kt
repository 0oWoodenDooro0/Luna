package website.woodendoor.repository

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Generic repository interface for CRUD operations.
 */
interface IRepository<T, ID> {
    fun create(entity: T)
    fun getById(id: ID): T?
    fun update(id: ID, entity: T)
    fun delete(id: ID)
    fun getAll(): List<T>
}

/**
 * Base Exposed-backed implementation of [IRepository].
 */
abstract class ExposedRepository<T, ID, TABLE : Table>(val table: TABLE) : IRepository<T, ID> {
    abstract fun toEntity(row: ResultRow): T
    abstract fun fromEntity(it: UpdateBuilder<*>, entity: T)
    abstract fun idColumn(): Column<ID>

    override fun create(entity: T) {
        transaction {
            table.insert {
                fromEntity(it, entity)
            }
        }
    }

    override fun getById(id: ID): T? {
        return transaction {
            table.selectAll().where { idColumn() eq id }.map { toEntity(it) }.singleOrNull()
        }
    }

    override fun update(id: ID, entity: T) {
        transaction {
            table.update({ idColumn() eq id }) {
                fromEntity(it, entity)
            }
        }
    }

    override fun delete(id: ID) {
        transaction {
            table.deleteWhere { idColumn() eq id }
        }
    }

    override fun getAll(): List<T> {
        return transaction {
            table.selectAll().map { toEntity(it) }
        }
    }
}
