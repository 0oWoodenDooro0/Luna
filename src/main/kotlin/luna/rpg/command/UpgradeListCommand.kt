package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.message.embed
import luna.core.Command
import luna.rpg.RpgConfig
import luna.rpg.repository.PlayerRepository

class UpgradeListCommand : Command {
    override val name = "upgrade_list"
    override val description = "查看所有裝備升級所需的資源與效果"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val player = PlayerRepository.getOrCreatePlayer(userId)

        val response = interaction.deferPublicResponse()
        response.respond {
            embed {
                title = "🛠️ 裝備升級清單"
                description = "目前的資源：🪵 ${player.wood} | 🪨 ${player.stone} | 🔗 ${player.metal}"

                val types =
                    listOf(
                        Triple("weapon", "⚔️ 武器 (Weapon)", player.weaponLevel),
                        Triple("shield", "🛡️ 盾牌 (Shield)", player.shieldLevel),
                        Triple("armor", "👕 護甲 (Armor)", player.armorLevel),
                        Triple("recovery", "❤️ 康復速度 (Recovery)", player.recoveryLevel),
                    )

                for ((key, displayName, level) in types) {
                    val requirements = RpgConfig.Economy.UPGRADE_REQUIREMENTS[key] ?: continue
                    val costLines =
                        requirements.map { (res, base) ->
                            val cost = (level + 1) * base
                            val current =
                                when (res) {
                                    "wood" -> player.wood
                                    "stone" -> player.stone
                                    "metal" -> player.metal
                                    else -> 0
                                }
                            val icon =
                                when (res) {
                                    "wood" -> "🪵"
                                    "stone" -> "🪨"
                                    "metal" -> "🔗"
                                    else -> ""
                                }
                            val status = if (current >= cost) "✅" else "❌"
                            "$status $icon $cost"
                        }

                    val effect =
                        when (key) {
                            "weapon" -> "+${RpgConfig.Upgrade.WEAPON_ATK_BONUS} ATK"
                            "shield" -> "+${RpgConfig.Upgrade.SHIELD_DEF_BONUS} DEF"
                            "armor" -> "+${RpgConfig.Upgrade.ARMOR_HP_BONUS} HP"
                            "recovery" -> "-${RpgConfig.Upgrade.RECOVERY_REDUCTION_SECONDS.toInt()}s 基礎冷卻"
                            else -> ""
                        }

                    field {
                        name = "$displayName (Lv.$level -> Lv.${level + 1})"
                        value = "效果：**$effect**\n成本：${costLines.joinToString(" | ")}"
                        inline = false
                    }
                }

                footer {
                    text = "使用 /upgrade [類型] 來進行升級"
                }
                color = dev.kord.common.Color(0xF1C40F)
            }
        }
    }
}
