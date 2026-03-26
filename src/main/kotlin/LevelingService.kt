package website.woodendoor

import website.woodendoor.repository.PlayerData
import website.woodendoor.repository.PlayerRepository

/**
 * Service for managing experience and leveling logic.
 */
object LevelingService {

    /**
     * Data class for level up result.
     */
    data class LevelUpResult(
        val newLevel: Int,
        val newXp: Int,
        val leveledUp: Boolean
    )

    /**
     * Gets the XP threshold for a level.
     */
    fun getXpThreshold(level: Int): Int {
        return level * 100
    }

    /**
     * Calculates the level up based on current level, xp, and added xp.
     */
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

    /**
     * Adds XP to a player and updates the database.
     */
    fun addXp(userId: String, xpToAdd: Int): LevelUpResult {
        val player = PlayerRepository.getById(userId) ?: throw IllegalArgumentException("Player not found")
        val result = calculateLevelUp(player.level, player.xp, xpToAdd)
        
        val updatedPlayer = player.copy(
            level = result.newLevel,
            xp = result.newXp
        )
        PlayerRepository.update(userId, updatedPlayer)
        
        return result
    }
}
