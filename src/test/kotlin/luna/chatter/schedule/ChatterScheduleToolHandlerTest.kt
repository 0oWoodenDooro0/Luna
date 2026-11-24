package luna.chatter.schedule

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatterScheduleToolHandlerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val taipeiZone = ZoneId.of("Asia/Taipei")

    private fun fixedClock(isoInstant: String = "2026-11-25T06:00:00Z"): Clock = Clock.fixed(Instant.parse(isoInstant), taipeiZone)

    private fun baseSchedule(date: LocalDate = LocalDate.of(2026, 11, 25)): DailySchedule =
        DailySchedule(
            date = date,
            timezone = "Asia/Taipei",
            blocks =
                listOf(
                    ScheduleBlock(
                        startMinute = 0,
                        endMinute = 480,
                        activityTitle = "睡覺",
                        activityDetail = "熟睡中",
                        statusText = "zzzz",
                        availability = AvailabilityState.SLEEPING,
                    ),
                    ScheduleBlock(
                        startMinute = 480,
                        endMinute = 1440,
                        activityTitle = "自由時間",
                        activityDetail = "在群組閒聊",
                        statusText = "滑手機中",
                        availability = AvailabilityState.AVAILABLE,
                    ),
                ),
            summary = "普通的一天",
            createdAtMillis = 1_000L,
        )

    private class FakeScheduleStore(
        initialSchedule: DailySchedule? = null,
    ) : DailyScheduleStore {
        var schedule: DailySchedule? = initialSchedule
        val commitments = linkedMapOf<String, ScheduleCommitment>()
        var throwOnSave: Boolean = false
        var throwOnUpsert: Boolean = false
        var throwOnList: Boolean = false

        override fun getSchedule(date: LocalDate): DailySchedule? = schedule?.takeIf { it.date == date }

        override fun saveSchedule(schedule: DailySchedule) {
            if (throwOnSave) throw RuntimeException("disk full")
            this.schedule = schedule
        }

        override fun getPreviousDaySummary(date: LocalDate): String? = null

        override fun upsertCommitment(commitment: ScheduleCommitment) {
            if (throwOnUpsert) throw RuntimeException("db locked")
            commitments[commitment.id] = commitment
        }

        override fun cancelCommitment(id: String): Boolean = commitments.remove(id) != null

        override fun listUpcomingCommitments(
            fromDate: LocalDate,
            toDateInclusive: LocalDate,
        ): List<ScheduleCommitment> {
            if (throwOnList) throw RuntimeException("db read error")
            return commitments.values
                .filter { !it.targetDate.isBefore(fromDate) && !it.targetDate.isAfter(toDateInclusive) }
                .sortedWith(compareBy({ it.targetDate }, { it.startMinute }))
        }

        override fun listCommitmentsForDate(date: LocalDate): List<ScheduleCommitment> =
            commitments.values.filter { it.targetDate == date }.sortedBy { it.startMinute }

        override fun recordPendingCatchUp(
            channelId: String,
            guildId: String,
            messageId: String,
            messageAtMillis: Long,
        ): CatchUpWatermark = error("unused")

        override fun getCatchUpWatermark(channelId: String): CatchUpWatermark? = null

        override fun listPendingCatchUps(): List<CatchUpWatermark> = emptyList()

        override fun clearCatchUpWatermark(channelId: String) {}
    }

    private fun createCoordinator(
        store: FakeScheduleStore,
        clock: Clock = fixedClock(),
        enabled: Boolean = true,
    ): DailyScheduleCoordinator =
        DailyScheduleCoordinator(
            store = store,
            generator =
                DailyScheduleGenerator(
                    interactionClient =
                        luna.core.ai.GeminiInteractionClient(
                            httpClient =
                                io.ktor.client.HttpClient(
                                    io.ktor.client.engine.mock
                                        .MockEngine { error("unused") },
                                ),
                        ),
                    model = "gemini-2.5-flash",
                    apiKey = "test-key",
                ),
            enabled = enabled,
            timezone = taipeiZone,
            clock = clock,
        )

    @Test
    fun `update_today_schedule applies valid patches and returns ok payload`() =
        runBlocking {
            // 2026-11-25T06:00:00Z is 14:00 in Asia/Taipei
            val store = FakeScheduleStore(baseSchedule())
            val coordinator = createCoordinator(store)
            val handler = ChatterScheduleToolHandler(coordinator)

            val args =
                buildJsonObject {
                    putJsonArray("patches") {
                        add(
                            buildJsonObject {
                                put("start_time", "15:00")
                                put("end_time", "17:00")
                                put("title", "一起打 APEX")
                                put("detail", "跟群友組隊排位")
                                put("status_text", "打 APEX 中")
                                put("availability", "AVAILABLE")
                            },
                        )
                    }
                }

            val raw = handler.handleTodayScheduleCall(args)
            val parsed = json.parseToJsonElement(raw).jsonObject
            assertEquals("ok", parsed["status"]?.jsonPrimitive?.content)
            assertEquals(1, parsed["applied_count"]?.jsonPrimitive?.content?.toInt())

            val patched = store.schedule?.blockAt(15 * 60)
            assertEquals("一起打 APEX", patched?.activityTitle)
            assertEquals(true, patched?.adjustedByConversation)
        }

    @Test
    fun `update_today_schedule rejects invalid time strings and missing patches without throwing`() =
        runBlocking {
            val store = FakeScheduleStore(baseSchedule())
            val coordinator = createCoordinator(store)
            val handler = ChatterScheduleToolHandler(coordinator)

            val invalidTimeArgs =
                buildJsonObject {
                    putJsonArray("patches") {
                        add(
                            buildJsonObject {
                                put("start_time", "25:99")
                                put("end_time", "17:00")
                                put("title", "打遊戲")
                                put("detail", "打遊戲")
                                put("status_text", "打遊戲")
                                put("availability", "AVAILABLE")
                            },
                        )
                    }
                }
            val invalidTimeParsed = json.parseToJsonElement(handler.handleTodayScheduleCall(invalidTimeArgs)).jsonObject
            assertEquals("invalid_argument", invalidTimeParsed["status"]?.jsonPrimitive?.content)
            assertTrue(
                invalidTimeParsed["message"]?.jsonPrimitive?.content?.contains("start_time") == true,
                "Expected error message to mention start_time: $invalidTimeParsed",
            )

            val emptyPatchesParsed =
                json
                    .parseToJsonElement(
                        handler.handleTodayScheduleCall(buildJsonObject { put("patches", buildJsonArray {}) }),
                    ).jsonObject
            assertEquals("invalid_argument", emptyPatchesParsed["status"]?.jsonPrimitive?.content)
        }

    @Test
    fun `update_today_schedule returns rejected payload when coordinator rejects guardrail`() =
        runBlocking {
            val store = FakeScheduleStore(baseSchedule())
            val coordinator = createCoordinator(store)
            val handler = ChatterScheduleToolHandler(coordinator)

            val pastPatchArgs =
                buildJsonObject {
                    putJsonArray("patches") {
                        add(
                            buildJsonObject {
                                put("start_time", "09:00")
                                put("end_time", "10:00")
                                put("title", "過去的活動")
                                put("detail", "測試")
                                put("status_text", "測試")
                                put("availability", "AVAILABLE")
                            },
                        )
                    }
                }
            val parsed = json.parseToJsonElement(handler.handleTodayScheduleCall(pastPatchArgs)).jsonObject
            assertEquals("rejected", parsed["status"]?.jsonPrimitive?.content)
            assertTrue(
                parsed["reason"]?.jsonPrimitive?.content?.isNotBlank() == true,
                "Expected non-blank rejection reason: $parsed",
            )
        }

    @Test
    fun `unexpected exception during tool execution logs warning and returns graceful error payload`() =
        runBlocking {
            val logger = LoggerFactory.getLogger(ChatterScheduleToolHandler::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            try {
                val throwingCoordinator =
                    object : DailyScheduleCoordinator(
                        store = FakeScheduleStore(baseSchedule()),
                        generator =
                            DailyScheduleGenerator(
                                interactionClient =
                                    luna.core.ai.GeminiInteractionClient(
                                        httpClient =
                                            io.ktor.client.HttpClient(
                                                io.ktor.client.engine.mock
                                                    .MockEngine { error("unused") },
                                            ),
                                    ),
                                model = "gemini-2.5-flash",
                                apiKey = "test-key",
                            ),
                        timezone = taipeiZone,
                        clock = fixedClock(),
                    ) {
                        override suspend fun applyTodayPatches(patches: List<SchedulePatch>): DailyScheduleSplicer.SpliceResult =
                            throw IllegalStateException("simulated boom")
                    }

                val handler = ChatterScheduleToolHandler(throwingCoordinator)
                val args =
                    buildJsonObject {
                        putJsonArray("patches") {
                            add(
                                buildJsonObject {
                                    put("start_time", "15:00")
                                    put("end_time", "16:00")
                                    put("title", "聊天")
                                    put("detail", "聊天")
                                    put("status_text", "聊天")
                                    put("availability", "AVAILABLE")
                                },
                            )
                        }
                    }

                val parsed = json.parseToJsonElement(handler.handleTodayScheduleCall(args)).jsonObject
                assertEquals("unavailable", parsed["status"]?.jsonPrimitive?.content)
                assertTrue(
                    appender.list.any { it.level == Level.WARN && it.formattedMessage.contains("simulated boom") },
                    "Expected WARN log with exception message",
                )
            } finally {
                logger.detachAppender(appender)
            }
        }

    @Test
    fun `manage_schedule_commitment upserts and cancels by id and by matching date and title`() =
        runBlocking {
            val store = FakeScheduleStore(baseSchedule())
            val coordinator = createCoordinator(store)
            val handler = ChatterScheduleToolHandler(coordinator)

            val upsertArgs =
                buildJsonObject {
                    put("action", "upsert")
                    put("date", "2026-11-26")
                    put("start_time", "20:00")
                    put("end_time", "22:00")
                    put("title", "跟大家看電影")
                    put("detail", "語音房一起看動畫電影")
                    put("status_text", "看電影中")
                    put("availability", "AVAILABLE")
                }
            val upsertParsed = json.parseToJsonElement(handler.handleCommitmentCall(upsertArgs)).jsonObject
            assertEquals("ok", upsertParsed["status"]?.jsonPrimitive?.content)
            assertEquals("upsert", upsertParsed["action"]?.jsonPrimitive?.content)
            val createdId =
                upsertParsed["commitment"]
                    ?.jsonObject
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.content
            assertTrue(!createdId.isNullOrBlank(), "Expected non-blank commitment id in $upsertParsed")
            assertEquals(1, store.commitments.size)

            // Cancel by matching date + title (omitting id)
            val cancelByMatchArgs =
                buildJsonObject {
                    put("action", "cancel")
                    put("date", "2026-11-26")
                    put("title", "跟大家看電影")
                }
            val cancelParsed = json.parseToJsonElement(handler.handleCommitmentCall(cancelByMatchArgs)).jsonObject
            assertEquals("ok", cancelParsed["status"]?.jsonPrimitive?.content)
            assertEquals("cancel", cancelParsed["action"]?.jsonPrimitive?.content)
            assertEquals(createdId, cancelParsed["id"]?.jsonPrimitive?.content)
            assertTrue(store.commitments.isEmpty(), "Expected commitments to be empty after cancel")
        }

    @Test
    fun `manage_schedule_commitment rejects malformed date, unknown action, and out-of-horizon date`() =
        runBlocking {
            val store = FakeScheduleStore(baseSchedule())
            val coordinator = createCoordinator(store)
            val handler = ChatterScheduleToolHandler(coordinator)

            val badAction =
                json
                    .parseToJsonElement(
                        handler.handleCommitmentCall(buildJsonObject { put("action", "delete") }),
                    ).jsonObject
            assertEquals("invalid_argument", badAction["status"]?.jsonPrimitive?.content)

            val badDate =
                json
                    .parseToJsonElement(
                        handler.handleCommitmentCall(
                            buildJsonObject {
                                put("action", "upsert")
                                put("date", "2026-02-30")
                                put("start_time", "10:00")
                                put("end_time", "11:00")
                                put("title", "測試")
                                put("detail", "測試")
                                put("status_text", "測試")
                                put("availability", "AVAILABLE")
                            },
                        ),
                    ).jsonObject
            assertEquals("invalid_argument", badDate["status"]?.jsonPrimitive?.content)

            // Today's date is rejected by coordinator guardrail
            val todayRejected =
                json
                    .parseToJsonElement(
                        handler.handleCommitmentCall(
                            buildJsonObject {
                                put("action", "upsert")
                                put("date", "2026-11-25")
                                put("start_time", "20:00")
                                put("end_time", "21:00")
                                put("title", "今天的約定")
                                put("detail", "測試")
                                put("status_text", "測試")
                                put("availability", "AVAILABLE")
                            },
                        ),
                    ).jsonObject
            assertEquals("rejected", todayRejected["status"]?.jsonPrimitive?.content)
        }
}
