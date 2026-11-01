package luna.undercover

import dev.kord.common.entity.Snowflake
import java.util.Collections
import java.util.LinkedHashMap

data class UndercoverGame(
    val players: Map<Snowflake, String>,
    val spy: Snowflake,
    val votes: MutableMap<Snowflake, Snowflake> = Collections.synchronizedMap(LinkedHashMap<Snowflake, Snowflake>()),
)

object UndercoverManager {
    private const val MAX_ACTIVE_GAMES = 100

    // Bounded synchronized LinkedHashMap with LRU eviction adhering to CODING_STANDARDS.md §1
    val activeGames: MutableMap<Snowflake, UndercoverGame> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Snowflake, UndercoverGame>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Snowflake, UndercoverGame>?): Boolean = size > MAX_ACTIVE_GAMES
            },
        )
}
