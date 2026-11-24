package luna.chatter

import luna.chatter.schedule.AvailabilityState
import luna.chatter.schedule.DailySchedule
import luna.chatter.schedule.DailyScheduleProvider
import luna.chatter.schedule.ScheduleBlock
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
        promptCue = "現在是深夜時段，有點想睡、腦袋更容易迷糊少根筋，會小聲碎念嫌晚又懶得打長文，但看到真正勾起好奇心的話題還是會忍不住簡短吐一兩句槽",
    ),
    MORNING(
        label = "早晨",
        thresholdOffset = 0.02,
        promptCue = "現在是早上時段，剛起床還在開機、有點迷糊慢半拍，話不算多但保持平常親切直率的語氣，像剛滑開 Discord 看看群組發生什麼事的樣子",
    ),
    DAYTIME(
        label = "白晝",
        thresholdOffset = 0.0,
        promptCue = "現在是下午白晝時段，精神在線、腦袋轉得快，對有趣的話題很有好奇心，隨時能機靈接話、自然吐槽或順手幫群友解圍",
    ),
    EVENING(
        label = "夜晚",
        thresholdOffset = -0.02,
        promptCue = "現在是晚上到凌晨初段，是一天中最放鬆有精神的群聊時段，特別有興致跟大家互嘴開玩笑，語氣活潑調皮又好相處",
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
        promptCue = "精神超好又自信滿滿，反應特別機靈調皮，愛接話吐槽也很樂意順手幫大家出主意",
    ),
    CHATTY(
        label = "CHATTY",
        thresholdOffset = -0.04,
        promptCue = "好奇心特別旺盛、很想跟群友聊天互動，語氣熱絡親近，看到有趣話題容易忍不住多搭一兩句",
    ),
    STEADY(
        label = "STEADY",
        thresholdOffset = 0.0,
        promptCue = "狀態自在平穩，維持平常機靈直率又帶點小調皮的群友節奏，有需要時自然幫忙",
    ),
    MELLOW(
        label = "MELLOW",
        thresholdOffset = 0.04,
        promptCue = "心情放鬆慢半拍，少了點愛鬧騰的衝勁，吐槽也比較輕柔隨性，但不失親近感",
    ),
    TIRED(
        label = "TIRED",
        thresholdOffset = 0.08,
        promptCue = "精神有點消耗、比較容易漏看細節或小抱怨一下覺得累，打字更精簡省力，但被順毛或遇到認真求助還是會願意回應",
    ),
    LOW_BATTERY(
        label = "LOW_BATTERY",
        thresholdOffset = 0.12,
        promptCue = "電量偏低、有點嫌麻煩想潛水放空，偶爾會嘴上小抗拒一下，但只要話題夠吸引人或朋友真的需要幫忙還是會簡短搭腔",
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
    val scheduleBlock: ScheduleBlock? = null,
    val dailySchedule: DailySchedule? = null,
)

/**
 * Provider seam supplying current [PersonaState] for Chatter participation evaluation and prompt building.
 */
interface PersonaStateProvider {
    fun currentState(baseThreshold: Double = ChatterConfig.DEFAULT_DECISION_THRESHOLD): PersonaState
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
    private val scheduleProvider: DailyScheduleProvider? = null,
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

        val activeScheduleProvider = scheduleProvider?.takeIf { it.isEnabled() }
        val activeSchedule = activeScheduleProvider?.currentSchedule()
        val activeBlock = activeScheduleProvider?.currentBlock()

        if (!enabled) {
            if (activeBlock != null) {
                val scheduleOffset = scheduleThresholdOffset(activeBlock.availability)
                return PersonaState(
                    driftEnabled = false,
                    timezone = resolvedTimezone,
                    localTime = localTime,
                    formattedWeekday = formattedWeekday,
                    formattedTime = formattedTime,
                    circadianPeriod = null,
                    mood = null,
                    baseThreshold = baseThreshold,
                    effectiveThreshold = computeEffectiveThreshold(baseThreshold, scheduleOffset, 0.0),
                    scheduleBlock = activeBlock,
                    dailySchedule = activeSchedule,
                )
            }
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

        val mood = resolveOrRollMood(now)

        if (activeBlock != null) {
            val scheduleOffset = scheduleThresholdOffset(activeBlock.availability)
            val effectiveThreshold = computeEffectiveThreshold(baseThreshold, scheduleOffset, mood.thresholdOffset)
            return PersonaState(
                driftEnabled = true,
                timezone = resolvedTimezone,
                localTime = localTime,
                formattedWeekday = formattedWeekday,
                formattedTime = formattedTime,
                circadianPeriod = null,
                mood = mood,
                baseThreshold = baseThreshold,
                effectiveThreshold = effectiveThreshold,
                scheduleBlock = activeBlock,
                dailySchedule = activeSchedule,
            )
        }

        val period = CircadianPeriod.fromHour(localTime.hour)
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
        const val BREAK_THRESHOLD_OFFSET = 0.06
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

        internal fun scheduleThresholdOffset(availability: AvailabilityState): Double =
            when (availability) {
                AvailabilityState.BREAK -> BREAK_THRESHOLD_OFFSET

                AvailabilityState.AVAILABLE,
                AvailabilityState.BUSY,
                AvailabilityState.SLEEPING,
                -> 0.0
            }

        internal fun computeEffectiveThreshold(
            baseThreshold: Double,
            periodOffset: Double,
            moodOffset: Double,
        ): Double {
            if (baseThreshold.isNaN() || baseThreshold.isInfinite()) {
                return ChatterConfig.DEFAULT_DECISION_THRESHOLD
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
