package website.woodendoor.rpg

object RpgConfig {
    /**
     * 每層樓固定的房間數量
     */
    const val FLOOR_SIZE = 5
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
        "armor" to mapOf("wood" to 5, "stone" to 10)
    )
}
