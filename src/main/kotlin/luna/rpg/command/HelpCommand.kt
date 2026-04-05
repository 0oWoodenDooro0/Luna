package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.message.embed
import luna.core.Command

class HelpCommand : Command {
    override val name = "help"
    override val description = "查看 RPG 指令列表與說明"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val response = interaction.deferPublicResponse()
        response.respond {
            embed {
                title = "📖 Luna RPG 指令幫助"
                description = "以下是目前可用的 RPG 指令列表："
                
                field {
                    name = "/explore"
                    value = "在目前的樓層進行探索，可能遇到怪物或發現資源。"
                    inline = false
                }
                field {
                    name = "/status"
                    value = "查看你的角色屬性、裝備等級、擁有資源以及重生進度。"
                    inline = false
                }
                field {
                    name = "/upgrade"
                    value = "消耗資源升級你的裝備（武器、盾牌、護甲、康復速度）。"
                    inline = false
                }
                field {
                    name = "/upgrade_list"
                    value = "查看所有裝備升級所需的資源、目前的等級與下一級的效果。"
                    inline = false
                }
                field {
                    name = "/rebirth"
                    value = "達到指定層數後可以重生，重置進度並獲得重生點數。"
                    inline = false
                }
                field {
                    name = "/rebirth_upgrade"
                    value = "消耗重生點數獲得永久的百分比屬性加成。"
                    inline = false
                }
                field {
                    name = "/rebirth_list"
                    value = "查看所有重生強化項目的點數成本與效果。"
                    inline = false
                }
                field {
                    name = "/settings"
                    value = "調整冒險設定，例如是否自動進入下一層。"
                    inline = false
                }
                field {
                    name = "/help"
                    value = "顯示此幫助訊息。"
                    inline = false
                }
                
                footer {
                    text = "祝你在 Luna 的冒險愉快！"
                }
                color = dev.kord.common.Color(0x3498DB)
            }
        }
    }
}
