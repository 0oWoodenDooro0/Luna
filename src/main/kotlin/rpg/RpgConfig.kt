package website.woodendoor.rpg

object RpgConfig {
    /**
     * 每層樓固定的房間數量
     */
    const val FLOOR_SIZE = 5

    /**
     * 康復基礎冷卻時間 (每 HP 秒數)
     */
    const val RECOVERY_BASE_SECONDS_PER_HP = 0.1

    /**
     * 康復速度升級每次減少的冷卻時間 (秒)
     */
    const val RECOVERY_UPGRADE_REDUCTION_SECONDS = 5.0

    /**
     * 最小康復時間 (秒)
     */
    const val RECOVERY_MIN_SECONDS = 5.0

/**
 * 裝備每級增加的屬性值
 */
const val EQUIPMENT_BONUS_PER_LEVEL = 5

/**
 * 各類裝備升級所需的資源類型與基礎數量
...
     * 最終花費 = (level + 1) * 基礎數量
     */
    val UPGRADE_REQUIREMENTS = mapOf(
        "weapon" to mapOf("wood" to 10, "metal" to 5),
        "shield" to mapOf("stone" to 10, "metal" to 5),
        "armor" to mapOf("wood" to 5, "stone" to 10),
        "recovery" to mapOf("wood" to 10, "stone" to 10, "metal" to 10)
    )
}
