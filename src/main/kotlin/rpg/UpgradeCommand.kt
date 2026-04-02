package website.woodendoor.rpg

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.string
import website.woodendoor.Command
import website.woodendoor.repository.PlayerRepository

class UpgradeCommand : Command {
    override val name = "upgrade"
    override val description = "升級你的裝備"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description) {
            string("type", "要升級的裝備類型") {
                required = true
                choice("武器 (Weapon)", "weapon")
                choice("盾牌 (Shield)", "shield")
                choice("護甲 (Armor)", "armor")
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val type = interaction.command.strings["type"] ?: return
        
        val result = PlayerRepository.upgradeEquipment(userId, type)
        val response = interaction.deferPublicResponse()
        
        when (result) {
            is PlayerRepository.UpgradeResult.Success -> {
                val player = result.player
                val newLevel = when (type) {
                    "weapon" -> player.weaponLevel
                    "shield" -> player.shieldLevel
                    "armor" -> player.armorLevel
                    else -> 0
                }
                val typeName = when (type) {
                    "weapon" -> "武器"
                    "shield" -> "盾牌"
                    "armor" -> "護甲"
                    else -> "裝備"
                }
                response.respond {
                    content = "✅ 升級成功！你的 **$typeName** 已提升至 **Lv.$newLevel**！"
                }
            }
            is PlayerRepository.UpgradeResult.InsufficientResources -> {
                response.respond {
                    content = "❌ 資源不足！升級需要 **${result.required}** 個 ${result.missingResource}，但你只有 **${result.current}** 個。"
                }
            }
            PlayerRepository.UpgradeResult.Error -> {
                response.respond {
                    content = "❌ 發生錯誤，無法進行升級。"
                }
            }
        }
    }
}
