package website.woodendoor.rpg

import kotlin.math.max

object CombatEngine {
    data class CombatResult(
        val won: Boolean,
        val playerFinalHP: Int,
        val monsterFinalHP: Int,
        val combatLog: List<String>
    )

    fun simulate(player: Player, monster: Monster, playerName: String = "玩家"): CombatResult {
        val combatLog = mutableListOf<String>()
        val effective = player.effectiveAttributes
        var playerHP = effective.hp
        var monsterHP = monster.attributes.hp

        combatLog.add("⚔️ 遭遇了 ${monster.name} (HP: $monsterHP)！")

        val entities = if (effective.spd >= monster.attributes.spd) {
            listOf("Player", "Monster")
        } else {
            listOf("Monster", "Player")
        }

        var turn = 1
        while (playerHP > 0 && monsterHP > 0 && turn <= RpgConfig.Combat.MAX_TURNS) {
            for (entity in entities) {
                if (entity == "Player") {
                    val dmg = max(1, effective.atk - monster.attributes.def)
                    monsterHP -= dmg
                    combatLog.add("回合 $turn: $playerName 攻擊 ${monster.name}，造成 $dmg 傷害！(${monster.name} HP: ${max(0, monsterHP)})")
                    if (monsterHP <= 0) break
                } else {
                    val dmg = max(1, monster.attributes.atk - effective.def)
                    playerHP -= dmg
                    combatLog.add("回合 $turn: ${monster.name} 攻擊 $playerName，造成 $dmg 傷害！($playerName HP: ${max(0, playerHP)})")
                    if (playerHP <= 0) break
                }
            }
            turn++
        }

        return CombatResult(
            won = monsterHP <= 0,
            playerFinalHP = max(0, playerHP),
            monsterFinalHP = max(0, monsterHP),
            combatLog = combatLog
        )
    }
}
