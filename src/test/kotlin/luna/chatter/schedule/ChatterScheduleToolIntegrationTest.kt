package luna.chatter.schedule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import luna.chatter.ChatterConfig
import luna.chatter.ChatterGenerator
import luna.chatter.ChatterInput
import luna.core.ai.AiAttemptStage
import luna.core.ai.AiTraceJson
import luna.core.ai.AiTraceRecord
import luna.core.ai.GeminiInteractionClient
import luna.core.ai.JsonlAiTraceRecorder
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatterScheduleToolIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
            explicitNulls = false
        }

    private val taipeiZone = ZoneId.of("Asia/Taipei")

    private fun createMockHttpClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

    private class InMemoryScheduleStore(
        initialSchedule: DailySchedule,
    ) : DailyScheduleStore {
        var schedule: DailySchedule = initialSchedule
        val commitments = linkedMapOf<String, ScheduleCommitment>()

        override fun getSchedule(date: LocalDate): DailySchedule? = schedule.takeIf { it.date == date }

        override fun saveSchedule(schedule: DailySchedule) {
            this.schedule = schedule
        }

        override fun getPreviousDaySummary(date: LocalDate): String? = null

        override fun upsertCommitment(commitment: ScheduleCommitment) {
            commitments[commitment.id] = commitment
        }

        override fun cancelCommitment(id: String): Boolean = commitments.remove(id) != null

        override fun listUpcomingCommitments(
            fromDate: LocalDate,
            toDateInclusive: LocalDate,
        ): List<ScheduleCommitment> =
            commitments.values
                .filter { !it.targetDate.isBefore(fromDate) && !it.targetDate.isAfter(toDateInclusive) }
                .sortedWith(compareBy({ it.targetDate }, { it.startMinute }))

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

    private fun createCoordinator(): Pair<InMemoryScheduleStore, DailyScheduleCoordinator> {
        val today = LocalDate.of(2026, 11, 25)
        val store =
            InMemoryScheduleStore(
                DailySchedule(
                    date = today,
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
                ),
            )
        val coordinator =
            DailyScheduleCoordinator(
                store = store,
                generator =
                    DailyScheduleGenerator(
                        interactionClient =
                            GeminiInteractionClient(
                                httpClient = createMockHttpClient(MockEngine { error("unused") }),
                            ),
                        model = "gemini-2.5-flash",
                        apiKey = "test-key",
                    ),
                enabled = true,
                timezone = taipeiZone,
                clock = Clock.fixed(Instant.parse("2026-11-25T06:00:00Z"), taipeiZone),
            )
        return store to coordinator
    }

    @Test
    fun `ChatterGenerator dispatches update_today_schedule and manage_schedule_commitment and records AI Trace continuation`() =
        runBlocking {
            val (store, coordinator) = createCoordinator()
            val scheduleToolHandler = ChatterScheduleToolHandler(coordinator)
            val traceDir = tempDir.resolve("traces")
            val traceRecorder = JsonlAiTraceRecorder(traceDir = traceDir)

            var callIndex = 0
            val requestBodies = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    requestBodies.add((request.body as TextContent).text)
                    callIndex++
                    val body =
                        when (callIndex) {
                            1 -> {
                                """
                                {
                                  "id": "inter-sched-1",
                                  "status": "requires_action",
                                  "steps": [
                                    {
                                      "type": "function_call",
                                      "id": "call-today-1",
                                      "name": "update_today_schedule",
                                      "arguments": {
                                        "patches": [
                                          {
                                            "start_time": "15:00",
                                            "end_time": "17:00",
                                            "title": "打 APEX",
                                            "detail": "跟群友組隊",
                                            "status_text": "打 APEX 中",
                                            "availability": "AVAILABLE"
                                          }
                                        ]
                                      }
                                    }
                                  ]
                                }
                                """.trimIndent()
                            }

                            2 -> {
                                """
                                {
                                  "id": "inter-sched-2",
                                  "status": "requires_action",
                                  "steps": [
                                    {
                                      "type": "function_call",
                                      "id": "call-commit-1",
                                      "name": "manage_schedule_commitment",
                                      "arguments": {
                                        "action": "upsert",
                                        "date": "2026-11-26",
                                        "start_time": "20:00",
                                        "end_time": "22:00",
                                        "title": "看電影",
                                        "detail": "語音房一起看電影",
                                        "status_text": "看電影中",
                                        "availability": "AVAILABLE"
                                      }
                                    }
                                  ]
                                }
                                """.trimIndent()
                            }

                            else -> {
                                """
                                {
                                  "id": "inter-sched-3",
                                  "status": "completed",
                                  "steps": [
                                    {
                                      "type": "model_output",
                                      "content": [
                                        {
                                          "type": "text",
                                          "text": "好啊 下午三點一起開打\n明天晚上看電影也先記下來了"
                                        }
                                      ]
                                    }
                                  ]
                                }
                                """.trimIndent()
                            }
                        }
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config =
                        ChatterConfig(
                            apiKey = "test-api-key",
                            schedule = ChatterScheduleConfig(enabled = true),
                        ),
                    httpClient = createMockHttpClient(mockEngine),
                    traceRecorder = traceRecorder,
                    scheduleToolHandler = scheduleToolHandler,
                )

            val reply =
                generator.generateReply(
                    ChatterInput(
                        transcript = "Alice (uid:111): Luna 下午三點打 APEX 嗎 順便約明晚八點看電影",
                        guildId = "1001",
                        channelId = "2002",
                        authorId = "111",
                        isDirectMention = true,
                    ),
                )

            assertEquals("好啊 下午三點一起開打\n明天晚上看電影也先記下來了", reply)
            assertEquals(3, callIndex)
            assertEquals("打 APEX", store.schedule.blockAt(15 * 60)?.activityTitle)
            assertEquals(1, store.commitments.size)
            assertEquals(
                "看電影",
                store.commitments.values
                    .first()
                    .activityTitle,
            )

            traceRecorder.close()

            // Verify AI Trace recorded CHATTER_TOOL_CONTINUATION
            val traceFile = Files.list(traceDir).use { stream -> stream.findFirst().orElseThrow() }
            val records =
                Files.readAllLines(traceFile).map { line ->
                    AiTraceJson.decodeFromString(AiTraceRecord.serializer(), line)
                }
            assertEquals(1, records.size)
            val stages = records.first().attempts.map { it.stage }
            assertTrue(AiAttemptStage.CHATTER_GENERATE in stages, "Expected CHATTER_GENERATE in $stages")
            assertTrue(AiAttemptStage.CHATTER_TOOL_CONTINUATION in stages, "Expected CHATTER_TOOL_CONTINUATION in $stages")
        }

    @Test
    fun `ChatterGenerator omits schedule tools when schedule config is disabled`() =
        runBlocking {
            val (_, coordinator) = createCoordinator()
            val scheduleToolHandler = ChatterScheduleToolHandler(coordinator)
            var capturedBody = ""
            val mockEngine =
                MockEngine { request ->
                    capturedBody = (request.body as TextContent).text
                    respond(
                        content =
                            """
                            {
                              "id": "inter-disabled-1",
                              "status": "completed",
                              "steps": [{"type": "model_output", "content": [{"type": "text", "text": "安安"}]}]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config =
                        ChatterConfig(
                            apiKey = "test-api-key",
                            searchGroundingEnabled = false,
                            urlContextEnabled = false,
                            schedule = ChatterScheduleConfig(enabled = false),
                        ),
                    httpClient = createMockHttpClient(mockEngine),
                    scheduleToolHandler = scheduleToolHandler,
                )

            val reply = generator.generateReply(ChatterInput(transcript = "Alice: 安安"))
            assertEquals("安安", reply)
            assertFalse(
                capturedBody.contains(ChatterScheduleToolHandler.TODAY_TOOL_NAME),
                "Did not expect ${ChatterScheduleToolHandler.TODAY_TOOL_NAME} in disabled request",
            )
            assertFalse(
                capturedBody.contains(ChatterScheduleToolHandler.COMMITMENT_TOOL_NAME),
                "Did not expect ${ChatterScheduleToolHandler.COMMITMENT_TOOL_NAME} in disabled request",
            )
        }

    @Test
    fun `ChatterGenerator passes guardrail rejection back to model without throwing`() =
        runBlocking {
            val (_, coordinator) = createCoordinator()
            val scheduleToolHandler = ChatterScheduleToolHandler(coordinator)

            var callIndex = 0
            val requestBodies = mutableListOf<String>()
            val mockEngine =
                MockEngine { request ->
                    requestBodies.add((request.body as TextContent).text)
                    callIndex++
                    val body =
                        if (callIndex == 1) {
                            """
                            {
                              "id": "inter-reject-1",
                              "status": "requires_action",
                              "steps": [
                                {
                                  "type": "function_call",
                                  "id": "call-past-1",
                                  "name": "update_today_schedule",
                                  "arguments": {
                                    "patches": [
                                      {
                                        "start_time": "09:00",
                                        "end_time": "10:00",
                                        "title": "補眠",
                                        "detail": "補眠",
                                        "status_text": "補眠",
                                        "availability": "SLEEPING"
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                            """.trimIndent()
                        } else {
                            """
                            {
                              "id": "inter-reject-2",
                              "status": "completed",
                              "steps": [
                                {
                                  "type": "model_output",
                                  "content": [
                                    {
                                      "type": "text",
                                      "text": "早上九點都過去了啦 時光不能倒流喔"
                                    }
                                  ]
                                }
                              ]
                            }
                            """.trimIndent()
                        }
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator =
                ChatterGenerator(
                    config =
                        ChatterConfig(
                            apiKey = "test-api-key",
                            schedule = ChatterScheduleConfig(enabled = true),
                        ),
                    httpClient = createMockHttpClient(mockEngine),
                    scheduleToolHandler = scheduleToolHandler,
                )

            val reply = generator.generateReply(ChatterInput(transcript = "Alice: 幫我把今天早上九點改成睡覺"))
            assertNotNull(reply)
            assertEquals("早上九點都過去了啦 時光不能倒流喔", reply)
            assertTrue(requestBodies[1].contains("rejected"), "Expected rejected payload in continuation request: ${requestBodies[1]}")
        }
}
