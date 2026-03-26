package website.woodendoor

object LevelingService {

    data class LevelUpResult(
        val newLevel: Int,
        val newXp: Int,
        val leveledUp: Boolean
    )

    fun getXpThreshold(level: Int): Int {
        return level * 100
    }

    fun calculateLevelUp(level: Int, xp: Int, addedXp: Int): LevelUpResult {
        var currentLevel = level
        var totalXp = xp + addedXp
        var threshold = getXpThreshold(currentLevel)
        var leveledUp = false

        while (totalXp >= threshold) {
            totalXp -= threshold
            currentLevel++
            leveledUp = true
            threshold = getXpThreshold(currentLevel)
        }

        return LevelUpResult(currentLevel, totalXp, leveledUp)
    }
}
