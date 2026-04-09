package luna.rpg

import luna.rpg.repository.PlayerMapRepository
import luna.rpg.repository.PlayerRepository

object MapService {
    sealed class CreateMapResult {
        data class Success(val mapId: Int) : CreateMapResult()
        data class InsufficientResources(val missing: List<ResourceCost>) : CreateMapResult()
        object InvalidParameters : CreateMapResult()
    }

    data class ResourceCost(val name: String, val required: Int, val current: Int)

    /**
     * Creates a new dungeon map for a player.
     * Validates resources and parameters before creation.
     */
    fun createMap(playerId: String, layer: Int, dropRate: Double): CreateMapResult {
        // Validation: Drop rate must be within configured range
        if (dropRate < RpgConfig.Map.MIN_DROP_RATE || dropRate > RpgConfig.Map.MAX_DROP_RATE) {
            return CreateMapResult.InvalidParameters
        }

        // Validation: Layer must be positive
        if (layer <= 0) {
            return CreateMapResult.InvalidParameters
        }

        val player = PlayerRepository.getOrCreatePlayer(playerId)
        val (woodCost, stoneCost, metalCost) = RpgConfig.Map.calculateCost(layer, dropRate)

        // Check for sufficient resources
        val missing = mutableListOf<ResourceCost>()
        if (player.wood < woodCost) missing.add(ResourceCost("木頭", woodCost, player.wood))
        if (player.stone < stoneCost) missing.add(ResourceCost("石頭", stoneCost, player.stone))
        if (player.metal < metalCost) missing.add(ResourceCost("金屬", metalCost, player.metal))

        if (missing.isNotEmpty()) {
            return CreateMapResult.InsufficientResources(missing)
        }

        // Deduct resources and create map via repository
        val mapId = PlayerMapRepository.createMap(playerId, layer, dropRate, woodCost, stoneCost, metalCost)
        return CreateMapResult.Success(mapId)
    }
}
