package luna.core

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.util.UUID

object UsersTable : Table("users") {
    val id = varchar("id", 64)
    val username = varchar("username", 64)
    val passwordHash = varchar("password_hash", 128)
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

data class User(
    val id: String,
    val username: String,
    val passwordHash: String,
    val createdAt: Long,
)

class UserStorage(
    private val database: Database,
) {
    init {
        transaction(database) {
            SchemaUtils.create(UsersTable)
        }
    }

    fun register(
        username: String,
        passwordRaw: String,
    ): User {
        val cleanUsername = username.trim()
        require(cleanUsername.length >= 3) { "使用者名稱至少需要 3 個字元" }
        require(passwordRaw.length >= 6) { "密碼至少需要 6 個字元" }

        val hash = hashPassword(passwordRaw)
        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        return transaction(database) {
            val existing = UsersTable.selectAll().where(UsersTable.username eq cleanUsername).singleOrNull()
            if (existing != null) {
                throw IllegalArgumentException("使用者名稱已被註冊")
            }

            UsersTable.insert {
                it[id] = newId
                it[UsersTable.username] = cleanUsername
                it[passwordHash] = hash
                it[createdAt] = now
            }

            User(newId, cleanUsername, hash, now)
        }
    }

    fun upsertDiscordUser(
        discordUserId: String,
        username: String,
    ): User {
        val now = System.currentTimeMillis()
        return transaction(database) {
            val existing = UsersTable.selectAll().where(UsersTable.id eq discordUserId).singleOrNull()
            if (existing != null) {
                User(
                    id = existing[UsersTable.id],
                    username = existing[UsersTable.username],
                    passwordHash = existing[UsersTable.passwordHash],
                    createdAt = existing[UsersTable.createdAt],
                )
            } else {
                UsersTable.insert {
                    it[id] = discordUserId
                    it[UsersTable.username] = username
                    it[passwordHash] = "DISCORD_OAUTH2"
                    it[createdAt] = now
                }
                User(discordUserId, username, "DISCORD_OAUTH2", now)
            }
        }
    }

    fun authenticate(
        username: String,
        passwordRaw: String,
    ): User? {
        val cleanUsername = username.trim()
        val hash = hashPassword(passwordRaw)

        return transaction(database) {
            val row = UsersTable.selectAll().where(UsersTable.username eq cleanUsername).singleOrNull() ?: return@transaction null
            if (row[UsersTable.passwordHash] == hash) {
                User(
                    id = row[UsersTable.id],
                    username = row[UsersTable.username],
                    passwordHash = row[UsersTable.passwordHash],
                    createdAt = row[UsersTable.createdAt],
                )
            } else {
                null
            }
        }
    }

    fun findById(id: String): User? {
        return transaction(database) {
            val row = UsersTable.selectAll().where(UsersTable.id eq id).singleOrNull() ?: return@transaction null
            User(
                id = row[UsersTable.id],
                username = row[UsersTable.username],
                passwordHash = row[UsersTable.passwordHash],
                createdAt = row[UsersTable.createdAt],
            )
        }
    }

    private fun hashPassword(password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("luna_salt_$password".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
