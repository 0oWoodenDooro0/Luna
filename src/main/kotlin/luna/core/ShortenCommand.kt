package luna.core

import com.github._0owoodendooro0.curtly.CurtlyService
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
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
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val command = interaction.command
        val url = command.strings["url"] ?: ""

        val response = interaction.deferPublicResponse()

        try {
            val shortUrl = curtlyService.shorten(url)
            response.respond {
                content = "🎉 縮網址成功！\n長網址：<$url>\n短網址：$shortUrl"
            }
            JsonLogger.log(
                layer = "COMMAND",
                component = "ShortenCommand",
                operation = "handle",
                data =
                    mapOf(
                        "userId" to interaction.user.id.toString(),
                        "url" to url,
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
