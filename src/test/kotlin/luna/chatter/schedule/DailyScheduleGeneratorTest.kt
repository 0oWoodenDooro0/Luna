package luna.chatter.schedule

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import luna.core.ai.GeminiInteractionClient
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DailyScheduleGeneratorTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-24T00:05:00Z"), ZoneOffset.UTC)

    private fun interactionSuccessResponse(text: String): String =
        buildJsonObject {
            put("id", "int-schedule-1")
            put("status", "completed")
            put(
                "steps",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "model_output")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", text)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }.toString()

    private fun createGenerator(
        engine: MockEngine,
        apiKey: String = "test-api-key",
    ): DailyScheduleGenerator {
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(json)
                }
            }
        val interactionClient = GeminiInteractionClient(httpClient = httpClient)
        return DailyScheduleGenerator(
            interactionClient = interactionClient,
            model = "gemini-3.5-flash-lite",
            apiKey = apiKey,
            baseUrl = "https://generativelanguage.googleapis.com",
            timezone = "Asia/Taipei",
            clock = fixedClock,
        )
    }

    @Test
    fun `parses valid fenced LLM JSON into contiguous 0 to 1440 DailySchedule with previous day continuity`() =
        runTest {
            var capturedBody = ""
            val payloadJson =
                """
                ```json
                {
                  "summary": "凌晨追番後補眠，下午趕接案插畫，晚上自由群聊",
                  "blocks": [
                    {
                      "startMinute": 0,
                      "endMinute": 120,
                      "activityTitle": "深夜追番",
                      "activityDetail": "泡熱可可配新番順便滑群組",
                      "statusText": "📺 深夜追番中",
                      "availability": "AVAILABLE"
                    },
                    {
                      "startMinute": 120,
                      "endMinute": 540,
                      "activityTitle": "補眠睡覺",
                      "activityDetail": "關燈鑽進被窩熟睡",
                      "statusText": "💤 補眠中",
                      "availability": "SLEEPING"
                    },
                    {
                      "startMinute": 540,
                      "endMinute": 840,
                      "activityTitle": "接案上色",
                      "activityDetail": "專心趕商稿細節",
                      "statusText": "🎨 趕稿中",
                      "availability": "BUSY"
                    },
                    {
                      "startMinute": 840,
                      "endMinute": 900,
                      "activityTitle": "下午茶休息",
                      "activityDetail": "起來伸懶腰喝冰拿鐵滑手機",
                      "statusText": "☕ 偷閒滑手機",
                      "availability": "BREAK"
                    },
                    {
                      "startMinute": 900,
                      "endMinute": 1440,
                      "activityTitle": "晚上自由時間",
                      "activityDetail": "打遊戲、看實況、跟群友耍廢聊天",
                      "statusText": "✨ 自由耍廢中",
                      "availability": "AVAILABLE"
                    }
                  ]
                }
                ```
                """.trimIndent()

            val engine =
                MockEngine { request ->
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = interactionSuccessResponse(payloadJson),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val generator = createGenerator(engine)
            val date = LocalDate.of(2026, 11, 24)
            val result = generator.generate(date = date, previousDaySummary = "昨天熬夜畫完草稿")

            assertTrue(result.isSuccess, "Expected valid JSON to succeed, got: ${result.exceptionOrNull()}")
            val schedule = result.getOrThrow()
            assertEquals(date, schedule.date)
            assertEquals("Asia/Taipei", schedule.timezone)
            assertEquals(5, schedule.blocks.size)
            assertEquals(AvailabilityState.AVAILABLE, schedule.blocks[0].availability)
            assertEquals(AvailabilityState.SLEEPING, schedule.blocks[1].availability)
            assertEquals(AvailabilityState.BUSY, schedule.blocks[2].availability)
            assertEquals(AvailabilityState.BREAK, schedule.blocks[3].availability)
            assertEquals(AvailabilityState.AVAILABLE, schedule.blocks[4].availability)
            assertTrue(capturedBody.contains("2026-11-24"), "Expected prompt to include target date")
            assertTrue(capturedBody.contains("昨天熬夜畫完草稿"), "Expected prompt to include previous day summary")
        }

    @Test
    fun `rejects gaps, overlaps, out-of-range minutes, missing fields, invalid availability, and HTTP errors without fallback`() =
        runTest {
            val invalidPayloads =
                listOf(
                    // Gap (480 -> 500)
                    """{"summary":"s","blocks":[{"startMinute":0,"endMinute":480,"activityTitle":"a","activityDetail":"d","statusText":"st","availability":"SLEEPING"},{"startMinute":500,"endMinute":1440,"activityTitle":"b","activityDetail":"d","statusText":"st","availability":"AVAILABLE"}]}""",
                    // Overlap (480 -> 400)
                    """{"summary":"s","blocks":[{"startMinute":0,"endMinute":480,"activityTitle":"a","activityDetail":"d","statusText":"st","availability":"SLEEPING"},{"startMinute":400,"endMinute":1440,"activityTitle":"b","activityDetail":"d","statusText":"st","availability":"AVAILABLE"}]}""",
                    // Negative startMinute
                    """{"summary":"s","blocks":[{"startMinute":-10,"endMinute":1440,"activityTitle":"a","activityDetail":"d","statusText":"st","availability":"AVAILABLE"}]}""",
                    // Out-of-range endMinute > 1440
                    """{"summary":"s","blocks":[{"startMinute":0,"endMinute":1500,"activityTitle":"a","activityDetail":"d","statusText":"st","availability":"AVAILABLE"}]}""",
                    // Missing statusText field
                    """{"summary":"s","blocks":[{"startMinute":0,"endMinute":1440,"activityTitle":"a","activityDetail":"d","availability":"AVAILABLE"}]}""",
                    // Missing summary
                    """{"blocks":[{"startMinute":0,"endMinute":1440,"activityTitle":"a","activityDetail":"d","statusText":"st","availability":"AVAILABLE"}]}""",
                    // Invalid availability enum
                    """{"summary":"s","blocks":[{"startMinute":0,"endMinute":1440,"activityTitle":"a","activityDetail":"d","statusText":"st","availability":"OFFLINE"}]}""",
                    // Malformed JSON
                    """not-json-at-all""",
                )

            for ((index, payload) in invalidPayloads.withIndex()) {
                val engine =
                    MockEngine {
                        respond(
                            content = interactionSuccessResponse(payload),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val result = createGenerator(engine).generate(LocalDate.of(2026, 11, 24))
                assertTrue(result.isFailure, "Payload #$index should fail validation without fallback")
            }

            // Upstream HTTP 500 failure
            val errorEngine =
                MockEngine {
                    respond(
                        content = """{"error":{"message":"Service Unavailable"}}""",
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val httpFailure = createGenerator(errorEngine).generate(LocalDate.of(2026, 11, 24))
            assertTrue(httpFailure.isFailure, "HTTP 503 must return failure without fabricating fallback schedule")

            // Blank API key
            val blankKeyResult = createGenerator(errorEngine, apiKey = " ").generate(LocalDate.of(2026, 11, 24))
            assertTrue(blankKeyResult.isFailure, "Blank API key must return failure without fabricating fallback schedule")
        }

    @Test
    fun `system instruction includes soft guidance for BUSY duration, interleaved BREAK rest, and ample AVAILABLE time`() =
        runTest {
            var capturedBody = ""
            val validPayload =
                """{"summary":"輕鬆的一天","blocks":[{"startMinute":0,"endMinute":1440,"activityTitle":"自由時間","activityDetail":"耍廢","statusText":"✨ 耍廢中","availability":"AVAILABLE"}]}"""
            val engine =
                MockEngine { request ->
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = interactionSuccessResponse(validPayload),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val result = createGenerator(engine).generate(LocalDate.of(2026, 11, 24))
            assertTrue(result.isSuccess, "Expected valid JSON to succeed, got: ${result.exceptionOrNull()}")
            assertEquals(2048, DailyScheduleGenerator.MAX_OUTPUT_TOKENS)

            assertTrue(
                capturedBody.contains("1–2 小時") || capturedBody.contains("1-2 小時"),
                "Expected system instruction to advise keeping continuous BUSY stretches around 1-2 hours",
            )
            assertTrue(
                (capturedBody.contains("15–45 分鐘") || capturedBody.contains("15-45 分鐘")) &&
                    capturedBody.contains("BREAK"),
                "Expected system instruction to advise interleaving 15-45 minutes of BREAK rest",
            )
            assertTrue(
                capturedBody.contains("AVAILABLE") && capturedBody.contains("BUSY"),
                "Expected system instruction to advise leaving ample AVAILABLE and BREAK time instead of packing BUSY",
            )
            assertTrue(
                capturedBody.contains("留出足夠") || capturedBody.contains("充裕"),
                "Expected system instruction to explicitly encourage leaving ample AVAILABLE/BREAK time for chatting",
            )
        }

    @Test
    fun `injects ScheduleCommitments into user prompt when provided`() =
        runTest {
            var capturedBody = ""
            val validPayload =
                """{"summary":"照約定打瓦的一天","blocks":[{"startMinute":0,"endMinute":1440,"activityTitle":"自由時間","activityDetail":"耍廢","statusText":"✨ 耍廢中","availability":"AVAILABLE"}]}"""
            val engine =
                MockEngine { request ->
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = interactionSuccessResponse(validPayload),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val date = LocalDate.of(2026, 11, 25)
            val commitment =
                ScheduleCommitment(
                    id = "c-gen-1",
                    targetDate = date,
                    startMinute = 1140,
                    endMinute = 1260,
                    activityTitle = "跟群友打瓦",
                    activityDetail = "約好晚上七點五排",
                    statusText = "🎮 打瓦中",
                    availability = AvailabilityState.AVAILABLE,
                    createdAtMillis = 1_700_000_000_000L,
                )

            val result = createGenerator(engine).generate(date = date, commitments = listOf(commitment))
            assertTrue(result.isSuccess, "Expected schedule generation with commitments to succeed")
            assertTrue(capturedBody.contains("19:00–21:00"), "Prompt must include commitment time range")
            assertTrue(capturedBody.contains("跟群友打瓦"), "Prompt must include commitment title")
            assertTrue(capturedBody.contains("約好晚上七點五排"), "Prompt must include commitment detail")
        }
}
