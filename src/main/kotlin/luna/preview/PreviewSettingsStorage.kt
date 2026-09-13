package luna.preview

import dev.socialpeek.model.Platform
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

object GuildPreviewSettingsTable : Table("guild_preview_settings") {
    val guildId = varchar("guild_id", 64)
    val enabled = bool("enabled").default(true)
    val disabledPlatforms = text("disabled_platforms").default("")
    val cleanUrlEnabled = bool("clean_url_enabled").default(true)
    val webhookReplaceEnabled = bool("webhook_replace_enabled").default(true)

    override val primaryKey = PrimaryKey(guildId)
}

class PreviewSettingsStorage(
    private val database: Database,
) {
    init {
        transaction(database) {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(GuildPreviewSettingsTable)
        }
    }

    fun isGuildEnabled(guildId: String): Boolean =
        transaction(database) {
            val row =
                GuildPreviewSettingsTable
                    .selectAll()
                    .where(GuildPreviewSettingsTable.guildId eq guildId)
                    .singleOrNull()
            row?.get(GuildPreviewSettingsTable.enabled) ?: true
        }

    fun isCleanUrlEnabled(guildId: String): Boolean =
        transaction(database) {
            val row =
                GuildPreviewSettingsTable
                    .selectAll()
                    .where(GuildPreviewSettingsTable.guildId eq guildId)
                    .singleOrNull()
            row?.get(GuildPreviewSettingsTable.cleanUrlEnabled) ?: true
        }

    fun setCleanUrlEnabled(
        guildId: String,
        enabled: Boolean,
    ) {
        transaction(database) {
            val existing =
                GuildPreviewSettingsTable
                    .selectAll()
                    .where(GuildPreviewSettingsTable.guildId eq guildId)
                    .singleOrNull()

            if (existing != null) {
                GuildPreviewSettingsTable.update({ GuildPreviewSettingsTable.guildId eq guildId }) {
                    it[cleanUrlEnabled] = enabled
                }
            } else {
                GuildPreviewSettingsTable.insert {
                    it[GuildPreviewSettingsTable.guildId] = guildId
                    it[GuildPreviewSettingsTable.enabled] = true
                    it[disabledPlatforms] = ""
                    it[cleanUrlEnabled] = enabled
                    it[webhookReplaceEnabled] = true
                }
            }
        }
    }

    fun isWebhookReplaceEnabled(guildId: String): Boolean =
        transaction(database) {
            val row =
                GuildPreviewSettingsTable
                    .selectAll()
                    .where(GuildPreviewSettingsTable.guildId eq guildId)
                    .singleOrNull()
            row?.get(GuildPreviewSettingsTable.webhookReplaceEnabled) ?: true
        }

    fun setWebhookReplaceEnabled(
        guildId: String,
        enabled: Boolean,
    ) {
        transaction(database) {
            val existing =
                GuildPreviewSettingsTable
                    .selectAll()
                    .where(GuildPreviewSettingsTable.guildId eq guildId)
                    .singleOrNull()

            if (existing != null) {
                GuildPreviewSettingsTable.update({ GuildPreviewSettingsTable.guildId eq guildId }) {
                    it[webhookReplaceEnabled] = enabled
                }
            } else {
                GuildPreviewSettingsTable.insert {
                    it[GuildPreviewSettingsTable.guildId] = guildId
                    it[GuildPreviewSettingsTable.enabled] = true
                    it[disabledPlatforms] = ""
                    it[cleanUrlEnabled] = true
                    it[webhookReplaceEnabled] = enabled
                }
            }
        }
    }

    fun getDisabledPlatforms(guildId: String): Set<Platform> {
        return transaction(database) {
            val row =
                GuildPreviewSettingsTable
                    .selectAll()
                    .where(GuildPreviewSettingsTable.guildId eq guildId)
                    .singleOrNull() ?: return@transaction emptySet()

            val raw = row[GuildPreviewSettingsTable.disabledPlatforms]
            if (raw.isBlank()) {
                emptySet()
            } else {
                raw
                    .split(",")
                    .mapNotNull { name ->
                        runCatching { Platform.valueOf(name.trim()) }.getOrNull()
                    }.toSet()
            }
        }
    }

    fun isPlatformEnabled(
        guildId: String,
        platform: Platform,
    ): Boolean {
        return transaction(database) {
            val row =
                GuildPreviewSettingsTable
                    .selectAll()
                    .where(GuildPreviewSettingsTable.guildId eq guildId)
                    .singleOrNull() ?: return@transaction true

            val guildEnabled = row[GuildPreviewSettingsTable.enabled]
            if (!guildEnabled) return@transaction false

            val disabled =
                row[GuildPreviewSettingsTable.disabledPlatforms]
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

            !disabled.contains(platform.name)
        }
    }

    fun setPlatformEnabled(
        guildId: String,
        platform: Platform,
        enabled: Boolean,
    ) {
        transaction(database) {
            val existing =
                GuildPreviewSettingsTable
                    .selectAll()
                    .where(GuildPreviewSettingsTable.guildId eq guildId)
                    .singleOrNull()

            val currentDisabled =
                existing
                    ?.get(GuildPreviewSettingsTable.disabledPlatforms)
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.toMutableSet() ?: mutableSetOf()

            if (enabled) {
                currentDisabled.remove(platform.name)
            } else {
                currentDisabled.add(platform.name)
            }

            val newDisabledStr = currentDisabled.joinToString(",")

            if (existing != null) {
                GuildPreviewSettingsTable.update({ GuildPreviewSettingsTable.guildId eq guildId }) {
                    it[disabledPlatforms] = newDisabledStr
                }
            } else {
                GuildPreviewSettingsTable.insert {
                    it[GuildPreviewSettingsTable.guildId] = guildId
                    it[GuildPreviewSettingsTable.enabled] = true
                    it[disabledPlatforms] = newDisabledStr
                    it[cleanUrlEnabled] = true
                    it[webhookReplaceEnabled] = true
                }
            }
        }
    }

    fun setGuildEnabled(
        guildId: String,
        enabled: Boolean,
    ) {
        transaction(database) {
            val existing =
                GuildPreviewSettingsTable
                    .selectAll()
                    .where(GuildPreviewSettingsTable.guildId eq guildId)
                    .singleOrNull()

            if (existing != null) {
                GuildPreviewSettingsTable.update({ GuildPreviewSettingsTable.guildId eq guildId }) {
                    it[GuildPreviewSettingsTable.enabled] = enabled
                }
            } else {
                GuildPreviewSettingsTable.insert {
                    it[GuildPreviewSettingsTable.guildId] = guildId
                    it[GuildPreviewSettingsTable.enabled] = enabled
                    it[disabledPlatforms] = ""
                    it[cleanUrlEnabled] = true
                    it[webhookReplaceEnabled] = true
                }
            }
        }
    }
}
