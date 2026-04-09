package luna.rpg.command

import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.ChatInputCommandInteraction
import dev.kord.rest.builder.message.embed
import luna.core.Command
import luna.rpg.CombatEngine
import luna.rpg.Monster
import luna.rpg.Player
import luna.rpg.RpgAttributes
import luna.rpg.RpgConfig
import luna.rpg.UpdateProgressionResult
import luna.rpg.repository.PlayerRepository
import kotlin.random.Random

class ExploreCommand : Command {
    override val name = "explore"
    override val description = "在目前的樓層進行探索"

    override suspend fun register(kord: Kord) {
        kord.createGlobalChatInputCommand(name, description)
    }

    override suspend fun handle(interaction: ChatInputCommandInteraction) {
        val userId = interaction.user.id.toString()
        val username = interaction.user.username

        // Use the returned player from restoreHpIfRecovered to ensure we have the latest state
        val player = PlayerRepository.restoreHpIfRecovered(userId) ?: PlayerRepository.getOrCreatePlayer(userId)

        val remaining = PlayerRepository.getRemainingRecoveryTime(player)
        if (remaining > 0) {
            interaction.deferPublicResponse().respond {
                embed {
                    title = "探索失敗"
                    description = "❤️ 你正在康復中... 剩餘時間: $remaining 秒。"
                    color = dev.kord.common.Color(0xFF0000)
                }
            }
            return
        }

        // Check for saved monster first
        val savedMonster = player.currentMonster
        if (savedMonster != null) {
            handleCombat(interaction, player, savedMonster, isResumption = true)
            return
        }

        val eventRoll = Random.nextInt(100)
        val currentFloor = player.currentFloor

        if (eventRoll < RpgConfig.Exploration.EVENT_ROLL_RESOURCE_THRESHOLD) {
            val resources = RpgConfig.Exploration.RESOURCE_NAMES
            val foundResource = resources.random()
            val baseAmount = Random.nextInt(RpgConfig.Exploration.RESOURCE_MIN_AMOUNT, RpgConfig.Exploration.RESOURCE_MAX_AMOUNT + 1)
            val playerBonus = player.calculateResourceBonus()
            val finalAmount = (baseAmount * playerBonus).toInt()

            val progressionResult = PlayerRepository.updateProgression(userId, player.currentFloor, player.roomsExplored)

            PlayerRepository.addResources(userId, foundResource, finalAmount)

            interaction.deferPublicResponse().respond {
                embed {
                    title = "探索結果：發現資源！"
                    description =
                        """
                        $username 在主要地層 第 $currentFloor 層探索中發現了 $foundResource x $finalAmount！
                        
                        進度：${progressionResult.finalRoomCount} / ${RpgConfig.Exploration.FLOOR_SIZE} 房間
                        ${progressionResult.message}
                        """.trimIndent()
                    color = dev.kord.common.Color(0x2ECC71)
                }
            }
        } else {
            val monsterNames = RpgConfig.Exploration.MONSTER_NAMES
            val monsterName = monsterNames.random()
            val floor = currentFloor

            val monsterAttr =
                RpgAttributes(
                    hp = RpgConfig.Monster.BASE_HP + (floor * RpgConfig.Monster.HP_PER_FLOOR),
                    maxHp = RpgConfig.Monster.BASE_HP + (floor * RpgConfig.Monster.HP_PER_FLOOR),
                    atk = RpgConfig.Monster.BASE_ATK + (floor * RpgConfig.Monster.ATK_PER_FLOOR),
                    def = RpgConfig.Monster.BASE_DEF + (floor * RpgConfig.Monster.DEF_PER_FLOOR),
                    spd = RpgConfig.Monster.BASE_SPD + (floor * RpgConfig.Monster.SPD_PER_FLOOR),
                )
            val monster = Monster(monsterName, monsterAttr)

            handleCombat(interaction, player, monster, isResumption = false)
        }
    }

    private suspend fun handleCombat(
        interaction: ChatInputCommandInteraction,
        player: Player,
        monster: Monster,
        isResumption: Boolean,
    ) {
        val userId = player.id
        val username = interaction.user.username

        val result = CombatEngine.simulate(player, monster, username)
        val won = result.won
        val currentFloor = player.currentFloor

        val reward =
            if (won) {
                val baseReward = PlayerRepository.calculateMonsterReward(currentFloor, player)
                baseReward.first to baseReward.second
            } else {
                null
            }

        val progressionResult =
            if (won) {
                PlayerRepository.updateProgression(userId, currentFloor, player.roomsExplored)
            } else {
                UpdateProgressionResult(player.roomsExplored, "")
            }

        PlayerRepository.recordCombatResult(userId, result.playerFinalHP, result.monsterFinalHP, monster, reward)

        interaction.deferPublicResponse().respond {
            embed {
                title =
                    if (won) {
                        if (isResumption) "探索結果：戰鬥勝利 (續戰)！" else "探索結果：戰鬥勝利！"
                    } else {
                        if (isResumption) "探索結果：戰鬥失敗 (續戰)" else "探索結果：戰鬥失敗"
                    }
                description =
                    """
                    ${if (isResumption) "🔄 繼續與 ${monster.name} 的戰鬥！" else ""}
                    ${result.combatLog.joinToString("\n")}
                    
                    ${if (won) "✨ 你擊敗了 ${monster.name}！並獲得了 **${reward?.first} x ${reward?.second}**！" else "💀 你被打敗了... 但你設法在同一個房間裡甦醒。"}
                    
                    進度：${progressionResult.finalRoomCount} / ${RpgConfig.Exploration.FLOOR_SIZE} 房間
                    ${progressionResult.message}
                    """.trimIndent()
                color = if (won) dev.kord.common.Color(0x00FF00) else dev.kord.common.Color(0xFF0000)
            }
        }
    }
}
