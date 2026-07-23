package luna.core

import com.github._0owoodendooro0.curtly.CurtlyService
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction

class MyUrlsCommand(
    private val curtlyService: CurtlyService,
    private val baseUrl: String,
) : Command {
    override val name = "myurls"
    override val description = "查看你透過 Discord 建立的所有短網址與點擊次數！"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val response = interaction.deferEphemeralResponse()

        try {
            val urls = curtlyService.getByOwnerId(userId)
            if (urls.isEmpty()) {
                response.respond {
                    content = "你目前尚未建立任何短網址！請使用 `/shorten` 建立一個吧。"
                }
                return
            }

            val cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val listContent =
                urls.entries.take(10).joinToString("\n") { (key, entry) ->
                    "• **$cleanBaseUrl$key** → <${entry.longUrl}> (點擊數: ${entry.clickCount})"
                }

            val extra = if (urls.size > 10) "\n*(僅顯示前 10 筆，總計 ${urls.size} 筆)*" else ""

            response.respond {
                content = "🔗 **你在此帳號建立的短網址清單：**\n$listContent$extra"
            }
        } catch (e: Exception) {
            response.respond {
                content = "❌ 讀取短網址失敗：${e.message}"
            }
        }
    }
}
