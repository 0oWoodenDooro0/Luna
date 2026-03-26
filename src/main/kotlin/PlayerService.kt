package website.woodendoor

import website.woodendoor.repository.PlayerData
import website.woodendoor.repository.PlayerRepository

/**
 * Service for managing player-related logic.
 */
object PlayerService {

    /**
     * Registers a player if not already registered.
     * @return true if registered successfully, false if already registered.
     */
    fun registerPlayer(userId: String): Boolean {
        if (PlayerRepository.getById(userId) != null) return false
        PlayerRepository.create(PlayerData(userId, 1, 0, 0, 0L))
        return true
    }

    /**
     * Gets a player by their ID.
     */
    fun getPlayer(userId: String): PlayerData? {
        return PlayerRepository.getById(userId)
    }
}
