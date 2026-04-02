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
     * 升級基礎花費 (level + 1) * BASE_COST
     */
    const val UPGRADE_BASE_COST = 10

    /**
     * 各類裝備升級所需的資源類型
     */
    val UPGRADE_REQUIREMENTS = mapOf(
        "weapon" to listOf("wood", "metal"),
        "shield" to listOf("stone", "metal"),
        "armor" to listOf("wood", "stone")
    )
}
