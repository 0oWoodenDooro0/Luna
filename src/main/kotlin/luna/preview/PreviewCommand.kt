package luna.preview

import dev.kord.common.entity.Permission
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.core.entity.interaction.SubCommand
import dev.kord.rest.builder.interaction.boolean
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.subCommand
import dev.socialpeek.model.Platform
import luna.core.Command

class PreviewCommand(
    private val storage: PreviewSettingsStorage,
) : Command {
    override val name = "preview"
    override val description = "設定伺服器社群貼文預覽功能（僅限管理員）"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            subCommand("status", "查看目前伺服器的各平台預覽開關狀態")
            subCommand("toggle", "開啟或關閉指定平台的預覽") {
                string("platform", "選擇要設定的社群平台") {
                    required = true
                    choice("全部平台 (總開關)", "ALL")
                    choice("X (Twitter)", "X")
                    choice("Bilibili", "BILIBILI")
                    choice("Threads", "THREADS")
                    choice("Instagram", "INSTAGRAM")
                    choice("Reddit", "REDDIT")
                    choice("YouTube", "YOUTUBE")
                }
                boolean("enabled", "是否開啟預覽") {
                    required = true
                }
            }
            subCommand("clean_url", "設定是否在偵測到追蹤參數時顯示乾淨連結提示") {
                boolean("enabled", "是否開啟乾淨連結提示") {
                    required = true
                }
            }
            subCommand("webhook_replace", "設定是否使用 Webhook 擬真替換使用者訊息（含乾淨連結與預覽）") {
                boolean("enabled", "是否開啟 Webhook 擬真替換") {
                    required = true
                }
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val rawGuildId = interaction.data.guildId.value
        if (rawGuildId == null) {
            val response = interaction.deferEphemeralResponse()
            response.respond { content = "此指令只能在伺服器中使用！" }
            return
        }

        val guildId = rawGuildId.toString()
        val member = interaction.user.asMemberOrNull(rawGuildId)
        val permissions = member?.getPermissions()
        val isManager =
            permissions?.contains(Permission.ManageGuild) == true ||
                permissions?.contains(Permission.Administrator) == true

        if (!isManager) {
            val response = interaction.deferEphemeralResponse()
            response.respond { content = "❌ 只有具備「管理伺服器」或「管理員」權限的成員可以查看與修改此設定！" }
            return
        }

        val subCommand = interaction.command as? SubCommand
        val subCommandName = subCommand?.name

        val response = interaction.deferEphemeralResponse()

        when (subCommandName) {
            "status" -> {
                val guildEnabled = storage.isGuildEnabled(guildId)
                val disabled = storage.getDisabledPlatforms(guildId)
                val cleanUrlEnabled = storage.isCleanUrlEnabled(guildId)
                val webhookReplaceEnabled = storage.isWebhookReplaceEnabled(guildId)

                val platforms =
                    listOf(
                        Platform.X to "X (Twitter)",
                        Platform.BILIBILI to "Bilibili",
                        Platform.THREADS to "Threads",
                        Platform.INSTAGRAM to "Instagram",
                        Platform.REDDIT to "Reddit",
                        Platform.YOUTUBE to "YouTube",
                    )

                val statusLines =
                    platforms.joinToString("\n") { (platform, displayName) ->
                        val isEnabled = guildEnabled && !disabled.contains(platform)
                        val icon = if (isEnabled) "✅" else "❌"
                        "- $icon **$displayName**"
                    }

                val totalStatus = if (guildEnabled) "✅ 開啟" else "❌ 停用"
                val cleanUrlStatus = if (cleanUrlEnabled) "✅ 開啟" else "❌ 關閉"
                val webhookStatus = if (webhookReplaceEnabled) "✅ 開啟" else "❌ 關閉"
                response.respond {
                    content =
                        """
                        ⚙️ **本伺服器社群預覽設定**
                        伺服器總開關：$totalStatus
                        Webhook 擬真替換：$webhookStatus
                        追蹤參數清洗提示：$cleanUrlStatus

                        各平台狀態：
                        $statusLines

                        *(可使用 `/preview toggle` 調整平台預覽，`/preview webhook_replace` 調整替換模式，或 `/preview clean_url` 調整乾淨連結提示)*
                        """.trimIndent()
                }
            }

            "toggle" -> {
                val platformKey = subCommand.strings["platform"]
                val enabled = subCommand.booleans["enabled"] ?: true

                if (platformKey == null) {
                    response.respond { content = "❌ 請指定要設定的平台！" }
                    return
                }

                if (platformKey == "ALL") {
                    storage.setGuildEnabled(guildId, enabled)
                    val statusText = if (enabled) "開啟" else "關閉"
                    response.respond { content = "✅ 已將本伺服器的社群預覽總開關設為：**$statusText**！" }
                } else {
                    val platform =
                        runCatching { Platform.valueOf(platformKey) }.getOrNull()
                    if (platform == null) {
                        response.respond { content = "❌ 未知的平台：`$platformKey`" }
                        return
                    }

                    storage.setPlatformEnabled(guildId, platform, enabled)
                    val statusText = if (enabled) "開啟" else "關閉"
                    response.respond {
                        content = "✅ 已將本伺服器的 **${platform.displayName}** 預覽設為：**$statusText**！"
                    }
                }
            }

            "clean_url" -> {
                val enabled = subCommand.booleans["enabled"] ?: true
                storage.setCleanUrlEnabled(guildId, enabled)
                val statusText = if (enabled) "開啟" else "關閉"
                response.respond {
                    content = "✅ 已將本伺服器的 **乾淨連結提示** 設定為：**$statusText**！"
                }
            }

            "webhook_replace" -> {
                val enabled = subCommand.booleans["enabled"] ?: true
                storage.setWebhookReplaceEnabled(guildId, enabled)
                val statusText = if (enabled) "開啟" else "關閉"
                response.respond {
                    content = "✅ 已將本伺服器的 **Webhook 擬真替換** 設定為：**$statusText**！"
                }
            }

            else -> {
                response.respond {
                    content = "請使用 `/preview status`、`/preview toggle`、`/preview webhook_replace` 或 `/preview clean_url`"
                }
            }
        }
    }
}
