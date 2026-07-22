package luna.core

import com.github._0owoodendooro0.curtly.CurtlyService
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.integer
import dev.kord.rest.builder.interaction.string

class ShortenCommand(
    private val curtlyService: CurtlyService,
) : Command {
    override val name = "shorten"
    override val description = "將長網址縮短為短網址！"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            string("url", "請輸入要縮短的完整長網址 (例如：https://google.com)") {
                required = true
            }
            string("custom_key", "自訂短網址別名 (選填)") {
                required = false
            }
            integer("expires_in", "有效時間，單位為秒 (選填)") {
                required = false
            }
            integer("max_clicks", "最大點擊次數限制 (選填)") {
                required = false
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val command = interaction.command
        val url = command.strings["url"] ?: ""
        val customKey = command.strings["custom_key"]?.ifBlank { null }
        val expiresInSeconds = command.integers["expires_in"]
        val maxClicks = command.integers["max_clicks"]?.toInt()

        val response = interaction.deferPublicResponse()

        try {
            val shortUrl =
                curtlyService.shorten(
                    longUrl = url,
                    customKey = customKey,
                    expiresInSeconds = expiresInSeconds,
                    maxClicks = maxClicks,
                )

            val details = mutableListOf<String>()
            if (customKey != null) details.add("自訂別名: `$customKey`")
            if (expiresInSeconds != null) details.add("有效時間: `${expiresInSeconds}秒`")
            if (maxClicks != null) details.add("點擊限制: `${maxClicks}次`")
            val extraInfo = if (details.isNotEmpty()) "\n設定: " + details.joinToString(" | ") else ""

            response.respond {
                content = "🎉 縮網址成功！\n長網址：<$url>\n短網址：$shortUrl$extraInfo"
            }
            JsonLogger.log(
                layer = "COMMAND",
                component = "ShortenCommand",
                operation = "handle",
                data =
                    mapOf(
                        "userId" to interaction.user.id.toString(),
                        "url" to url,
                        "customKey" to (customKey ?: ""),
                        "expiresInSeconds" to (expiresInSeconds ?: 0),
                        "maxClicks" to (maxClicks ?: 0),
                        "shortUrl" to shortUrl,
                    ),
            )
        } catch (e: Exception) {
            response.respond {
                content = "❌ 縮網址失敗：${e.message}"
            }
            JsonLogger.error(
                layer = "COMMAND",
                component = "ShortenCommand",
                operation = "handle",
                data =
                    mapOf(
                        "userId" to interaction.user.id.toString(),
                        "url" to url,
                    ),
                errorMessage = e.message,
            )
        }
    }
}
