package luna.chatter

import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Four daily circadian windows for Luna's in-memory persona state and participation threshold offset.
 */
enum class CircadianPeriod(
    val label: String,
    val thresholdOffset: Double,
    val promptCue: String,
) {
    LATE_NIGHT(
        label = "深夜",
        thresholdOffset = 0.08,
        promptCue = "現在是深夜時段，你有點想睡、語氣偏慵懶簡短，只對真正有趣或直接相關的話題偶爾回一兩句，多用極短句",
    ),
    MORNING(
        label = "早晨",
        thresholdOffset = 0.02,
        promptCue = "現在是早上時段，剛醒來不久還在暖機，語氣自然清爽但話不算多，像剛打開 Discord 看群組的樣子",
    ),
    DAYTIME(
        label = "白晝",
        thresholdOffset = 0.0,
        promptCue = "現在是下午白晝時段，精神在線、反應正常活躍，看到有興趣的話題會自然接話互動",
    ),
    EVENING(
        label = "夜晚",
        thresholdOffset = -0.02,
        promptCue = "現在是晚上到凌晨初段，是一天中最放鬆活躍的群聊時段，語氣隨性親切、比較樂意跟大家閒聊接話",
    ),
    ;

    companion object {
        fun fromHour(hour: Int): CircadianPeriod =
            when (hour) {
                in 1..5 -> LATE_NIGHT
                in 6..11 -> MORNING
                in 12..17 -> DAYTIME
                else -> EVENING
            }
    }
}

/**
 * Transient in-memory mood/energy states shifting every 2–4 hours (`-0.08` to `+0.12` threshold offset).
 */
enum class PersonaMood(
    val label: String,
    val thresholdOffset: Double,
    val promptCue: String,
) {
    ENERGETIC(
        label = "ENERGETIC",
        thresholdOffset = -0.08,
        promptCue = "心情不錯且精神很好，語氣輕快活潑，接話意願高",
    ),
    CHATTY(
        label = "CHATTY",
        thresholdOffset = -0.04,
        promptCue = "現在滿想跟群友聊天的，語氣親切熱情、帶點好奇心",
    ),
    STEADY(
        label = "STEADY",
        thresholdOffset = 0.0,
        promptCue = "情緒平穩自然，維持平常自在的聊天節奏",
    ),
    MELLOW(
        label = "MELLOW",
        thresholdOffset = 0.04,
        promptCue = "情緒偏平靜放鬆，語氣隨意淡定、不刻意搶話",
    ),
    TIRED(
        label = "TIRED",
        thresholdOffset = 0.08,
        promptCue = "精神稍微有點累，說話更精簡省力，話不多",
    ),
    LOW_BATTERY(
        label = "LOW_BATTERY",
        thresholdOffset = 0.12,
        promptCue = "今天偏安靜想潛水放空，只有很吸引人的話題才簡短搭腔",
    ),
}

/**
 * Snapshot of Luna's active persona state and effective participation threshold at evaluation time.
 */
data class PersonaState(
    val driftEnabled: Boolean,
    val timezone: String,
    val localTime: ZonedDateTime,
    val formattedWeekday: String,
    val formattedTime: String,
    val circadianPeriod: CircadianPeriod?,
    val mood: PersonaMood?,
    val baseThreshold: Double,
    val effectiveThreshold: Double,
)

/**
 * Provider seam supplying current [PersonaState] for Chatter participation evaluation and prompt building.
 */
interface PersonaStateProvider {
    fun currentState(baseThreshold: Double = ChatterDecisionConfig.DEFAULT_THRESHOLD): PersonaState
}

/**
 * In-memory `Circadian Persona Drift` provider parameterized by an injectable [Clock] and `CHATTER_TIMEZONE`.
 *
 * Maps local time into four circadian windows (`01:00–05:59` +0.08, `06:00–11:59` +0.02,
 * `12:00–17:59` 0.0, `18:00–00:59` -0.02), re-rolls transient mood/energy every `2–4` hours
 * (`-0.08` to `+0.12` offset) without database persistence, and clamps `effectiveThreshold`
 * strictly to `[0.05, 0.98]` when enabled.
 */
class CircadianPersonaDrift(
    timezone: String = DEFAULT_TIMEZONE,
    val enabled: Boolean = DEFAULT_DRIFT_ENABLED,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) : PersonaStateProvider {
    val zoneId: ZoneId = resolveZoneId(timezone)
    val resolvedTimezone: String = zoneId.id

    private val lock = Any()
    private var currentMood: PersonaMood? = null
    private var moodExpiresAt: Instant? = null

    override fun currentState(baseThreshold: Double): PersonaState {
        val now = clock.instant()
        val localTime = now.atZone(zoneId)
        val formattedWeekday = formatWeekday(localTime.dayOfWeek)
        val formattedTime = TIME_FORMATTER.format(localTime)

        if (!enabled) {
            return PersonaState(
                driftEnabled = false,
                timezone = resolvedTimezone,
                localTime = localTime,
                formattedWeekday = formattedWeekday,
                formattedTime = formattedTime,
                circadianPeriod = null,
                mood = null,
                baseThreshold = baseThreshold,
                effectiveThreshold = baseThreshold,
            )
        }

        val period = CircadianPeriod.fromHour(localTime.hour)
        val mood = resolveOrRollMood(now)
        val effectiveThreshold = computeEffectiveThreshold(baseThreshold, period.thresholdOffset, mood.thresholdOffset)

        return PersonaState(
            driftEnabled = true,
            timezone = resolvedTimezone,
            localTime = localTime,
            formattedWeekday = formattedWeekday,
            formattedTime = formattedTime,
            circadianPeriod = period,
            mood = mood,
            baseThreshold = baseThreshold,
            effectiveThreshold = effectiveThreshold,
        )
    }

    private fun resolveOrRollMood(now: Instant): PersonaMood =
        synchronized(lock) {
            val activeMood = currentMood
            val expiresAt = moodExpiresAt
            if (activeMood != null && expiresAt != null && now.isBefore(expiresAt)) {
                return activeMood
            }

            val candidates =
                if (activeMood == null) {
                    PersonaMood.entries
                } else {
                    PersonaMood.entries.filter { it != activeMood }.ifEmpty { PersonaMood.entries }
                }
            val nextMood = candidates[random.nextInt(candidates.size)]
            val durationMillis = random.nextLong(MIN_MOOD_INTERVAL_MILLIS, MAX_MOOD_INTERVAL_MILLIS + 1L)

            currentMood = nextMood
            moodExpiresAt = now.plusMillis(durationMillis)
            nextMood
        }

    companion object {
        private val logger = LoggerFactory.getLogger(CircadianPersonaDrift::class.java)

        const val DEFAULT_TIMEZONE = "Asia/Taipei"
        const val DEFAULT_DRIFT_ENABLED = true
        const val MIN_EFFECTIVE_THRESHOLD = 0.05
        const val MAX_EFFECTIVE_THRESHOLD = 0.98
        const val MIN_MOOD_INTERVAL_HOURS = 2L
        const val MAX_MOOD_INTERVAL_HOURS = 4L
        const val MIN_MOOD_INTERVAL_MILLIS = MIN_MOOD_INTERVAL_HOURS * 3_600_000L
        const val MAX_MOOD_INTERVAL_MILLIS = MAX_MOOD_INTERVAL_HOURS * 3_600_000L

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun resolveZoneId(rawTimezone: String?): ZoneId {
            val trimmed = rawTimezone?.trim()?.ifBlank { null } ?: return ZoneId.of(DEFAULT_TIMEZONE)
            return try {
                ZoneId.of(trimmed)
            } catch (e: Exception) {
                logger.warn(
                    "Invalid CHATTER_TIMEZONE '{}'; falling back to default '{}': {}",
                    trimmed,
                    DEFAULT_TIMEZONE,
                    e.message,
                )
                ZoneId.of(DEFAULT_TIMEZONE)
            }
        }

        internal fun computeEffectiveThreshold(
            baseThreshold: Double,
            periodOffset: Double,
            moodOffset: Double,
        ): Double {
            if (baseThreshold.isNaN() || baseThreshold.isInfinite()) {
                return ChatterDecisionConfig.DEFAULT_THRESHOLD
            }
            val sum =
                BigDecimal
                    .valueOf(baseThreshold)
                    .add(BigDecimal.valueOf(periodOffset))
                    .add(BigDecimal.valueOf(moodOffset))
                    .toDouble()
            return sum.coerceIn(MIN_EFFECTIVE_THRESHOLD, MAX_EFFECTIVE_THRESHOLD)
        }

        internal fun formatWeekday(dayOfWeek: DayOfWeek): String =
            when (dayOfWeek) {
                DayOfWeek.MONDAY -> "星期一"
                DayOfWeek.TUESDAY -> "星期二"
                DayOfWeek.WEDNESDAY -> "星期三"
                DayOfWeek.THURSDAY -> "星期四"
                DayOfWeek.FRIDAY -> "星期五"
                DayOfWeek.SATURDAY -> "星期六"
                DayOfWeek.SUNDAY -> "星期日"
            }
    }
}
