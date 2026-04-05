package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.message.embed
import luna.core.Command
import luna.rpg.*
import luna.rpg.repository.PlayerRepository

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
                choice("康復速度 (Recovery)", "recovery")
            }
        }
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val type = interaction.command.strings["type"] ?: return
        
        val result = PlayerRepository.upgradeEquipment(userId, type)
        val response = interaction.deferPublicResponse()
        
        val typeName = when (type) {
            "weapon" -> "武器"
            "shield" -> "盾牌"
            "armor" -> "護甲"
            "recovery" -> "康復速度"
            else -> "裝備"
        }

        when (result) {
            is PlayerRepository.UpgradeResult.Success -> {
                val player = result.player
                val newLevel = when (type) {
                    "weapon" -> player.weaponLevel
                    "shield" -> player.shieldLevel
                    "armor" -> player.armorLevel
                    "recovery" -> player.recoveryLevel
                    else -> 0
                }
                
                val statBonus = when (type) {
                    "weapon" -> "⚔️ ATK +${RpgConfig.Upgrade.WEAPON_ATK_BONUS}"
                    "shield" -> "🛡️ DEF +${RpgConfig.Upgrade.SHIELD_DEF_BONUS}"
                    "armor" -> "👕 HP +${RpgConfig.Upgrade.ARMOR_HP_BONUS}"
                    "recovery" -> "❤️ 康復速度 -${RpgConfig.Upgrade.RECOVERY_REDUCTION_SECONDS.toInt()}s"
                    else -> ""
                }

                response.respond {
                    embed {
                        title = "✅ 升級成功！"
                        description = "你的 **$typeName** 已提升至 **Lv.$newLevel**！"
                        color = dev.kord.common.Color(0x2ECC71)
                        field {
                            name = "屬性變化"
                            value = statBonus
                        }
                    }
                }
            }
            is PlayerRepository.UpgradeResult.InsufficientResources -> {
                response.respond {
                    embed {
                        title = "❌ 資源不足"
                        description = "升級需要 **${result.required}** 個 ${result.missingResource}，但你只有 **${result.current}** 個。"
                        color = dev.kord.common.Color(0xE74C3C)
                    }
                }
            }
            PlayerRepository.UpgradeResult.Error -> {
                response.respond {
                    embed {
                        title = "❌ 發生錯誤"
                        description = "無法進行升級。"
                        color = dev.kord.common.Color(0x95A5A6)
                    }
                }
            }
        }
    }
}
