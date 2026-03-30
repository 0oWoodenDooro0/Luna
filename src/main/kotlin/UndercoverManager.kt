package website.woodendoor

import dev.kord.cache.api.ConcurrentHashMap
import dev.kord.common.entity.Snowflake

data class UndercoverGame(
    val players: Map<Snowflake, String>,
    val spy: Snowflake,
    val votes: MutableMap<Snowflake, Snowflake>
)

object UndercoverManager {
    val activeGames = ConcurrentHashMap<Snowflake, UndercoverGame>()
}
