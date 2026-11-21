package luna.chatter.emoji

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import luna.chatter.GuildCustomEmoji
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * In-memory TTL cache for Luna's Discord Application Emojis.
 *
 * Discord emits no Gateway event for Application Emoji CRUD, so this cache refreshes
 * automatically after [ttlMillis] (default 10 minutes), serves stale snapshots on
 * transient failures without caching errors, and notifies callers when newly discovered
 * emoji IDs appear so background vision pre-scanning can run without blocking turns.
 */
internal class ApplicationEmojiCache(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(ApplicationEmojiCache::class.java)
    private val refreshMutex = Mutex()

    private data class Snapshot(
        val emojis: List<GuildCustomEmoji>,
        val ids: Set<String>,
        val fetchedAtMillis: Long,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    init {
        require(ttlMillis > 0L) { "ttlMillis must be positive, got $ttlMillis" }
    }

    /**
     * Returns the set of Application Emoji IDs currently held in the cached snapshot,
     * or an empty set if no successful fetch has completed yet.
     */
    fun knownIds(): Set<String> = snapshot?.ids ?: emptySet()

    /**
     * Returns the cached Application Emoji list when within [ttlMillis]; otherwise refreshes
     * via [fetcher]. Invokes [onNewEmojisDiscovered] outside the refresh lock whenever a
     * successful fetch contains emoji IDs absent from the previous snapshot.
     */
    suspend fun getOrRefresh(
        fetcher: suspend () -> List<GuildCustomEmoji>,
        onNewEmojisDiscovered: ((List<GuildCustomEmoji>) -> Unit)? = null,
    ): List<GuildCustomEmoji> {
        val current = snapshot
        if (current != null && !isExpired(current)) {
            return current.emojis
        }

        val (result, newlyDiscovered) =
            refreshMutex.withLock {
                val rechecked = snapshot
                if (rechecked != null && !isExpired(rechecked)) {
                    return@withLock rechecked.emojis to emptyList()
                }
                refreshLocked(fetcher)
            }

        if (newlyDiscovered.isNotEmpty()) {
            onNewEmojisDiscovered?.invoke(newlyDiscovered)
        }
        return result
    }

    /**
     * Unconditionally refreshes the cached Application Emoji snapshot via [fetcher].
     */
    suspend fun refresh(
        fetcher: suspend () -> List<GuildCustomEmoji>,
        onNewEmojisDiscovered: ((List<GuildCustomEmoji>) -> Unit)? = null,
    ): List<GuildCustomEmoji> {
        val (result, newlyDiscovered) =
            refreshMutex.withLock {
                refreshLocked(fetcher)
            }

        if (newlyDiscovered.isNotEmpty()) {
            onNewEmojisDiscovered?.invoke(newlyDiscovered)
        }
        return result
    }

    private fun isExpired(entry: Snapshot): Boolean = (clock.millis() - entry.fetchedAtMillis) >= ttlMillis

    private suspend fun refreshLocked(fetcher: suspend () -> List<GuildCustomEmoji>): Pair<List<GuildCustomEmoji>, List<GuildCustomEmoji>> {
        val previous = snapshot
        val fetched =
            try {
                fetcher()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (previous != null) {
                    logger.warn(
                        "Failed to refresh Application Emojis; serving stale snapshot ({} emoji(s)) without caching error: {}",
                        previous.emojis.size,
                        e.message,
                    )
                    return previous.emojis to emptyList()
                }
                logger.warn(
                    "Failed to fetch Application Emojis; returning empty list without caching error: {}",
                    e.message,
                )
                return emptyList<GuildCustomEmoji>() to emptyList()
            }

        val normalized = fetched.distinctBy { it.id }
        val previousIds = previous?.ids ?: emptySet()
        val newlyDiscovered = normalized.filter { it.id.toString() !in previousIds }
        snapshot =
            Snapshot(
                emojis = normalized,
                ids = normalized.map { it.id.toString() }.toSet(),
                fetchedAtMillis = clock.millis(),
            )
        return normalized to newlyDiscovered
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 10L * 60L * 1000L
    }
}
