package website.woodendoor

import website.woodendoor.repository.PlayerRepository
import website.woodendoor.repository.PlayerData

/**
 * Service for managing hunting logic.
 */
object HuntService {

    /**
     * Processes a hunt for a player.
     * @return [HuntResult] based on the processing outcome.
     */
    fun processHunt(userId: String): HuntResult {
        val player = PlayerRepository.getById(userId) ?: return HuntResult.NotRegistered
        val currentTime = System.currentTimeMillis()
        val cooldownMillis = 60 * 1000
        
        val timePassed = currentTime - player.lastHuntTime
        if (timePassed < cooldownMillis) {
            return HuntResult.OnCooldown((cooldownMillis - timePassed) / 1000)
        }
        
        val levelUpResult = LevelingService.calculateLevelUp(player.level, player.xp, 20)
        val newGold = player.gold + 10
        
        val updatedPlayer = player.copy(
            level = levelUpResult.newLevel,
            xp = levelUpResult.newXp,
            gold = newGold,
            lastHuntTime = currentTime
        )
        PlayerRepository.update(userId, updatedPlayer)
        
        return HuntResult.Success(newGold, levelUpResult)
    }

    /**
     * Result of a hunt processing.
     */
    sealed class HuntResult {
        /**
         * Successfully hunted.
         */
        data class Success(val newGold: Int, val levelUpResult: LevelingService.LevelUpResult) : HuntResult()
        
        /**
         * Hunt is on cooldown.
         */
        data class OnCooldown(val remainingSeconds: Long) : HuntResult()
        
        /**
         * Player is not registered.
         */
        object NotRegistered : HuntResult()
    }
}
