package luna.summarizer

import dev.kord.core.Kord
import dev.kord.core.entity.ReactionEmoji
import dev.kord.core.event.Event
import io.ktor.server.routing.Routing
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import luna.config.ConfigValidationException
import luna.config.EnvReader
import luna.plugin.DefaultPluginContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SummarizerPluginTest {
    private fun createTestDb(): Database = mockk(relaxed = true)

    @Test
    fun `initializes with default configurations when optional configs are absent`() =
        runTest {
            val listenerJob = Job()
            val testScope = CoroutineScope(Dispatchers.Unconfined + listenerJob)
            val mockKord = mockk<Kord>(relaxed = true)
            every { mockKord.events } returns MutableSharedFlow()
            every { mockKord.coroutineContext } returns testScope.coroutineContext

            val mockRouting = mockk<Routing>(relaxed = true)
            val testDb = createTestDb()

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockRouting,
                    database = testDb,
                    env = EnvReader.of(),
                )

            val plugin = SummarizerPlugin()
            plugin.initialize(context)

            assertEquals(SummaryService.DEFAULT_EMOJI, plugin.configuredEmoji)
            assertEquals(ConversationSliceCollector.DEFAULT_MAX_HISTORY_MESSAGES, plugin.maxHistoryMessages)
            assertEquals(ConversationSliceCollector.DEFAULT_INACTIVITY_GAP, plugin.inactivityGap)
            assertEquals(ConversationSliceCollector.DEFAULT_MAX_CHARACTER_BUDGET, plugin.maxCharacterBudget)
            assertEquals(ConversationSliceCollector.DEFAULT_MAX_LOOKBACK_MESSAGES, plugin.maxLookbackMessages)
            assertEquals(ConversationSliceCollector.DEFAULT_MAX_IMAGES, plugin.maxImages)
            assertNotNull(plugin.summarizer)
            assertNotNull(plugin.summaryService)

            listenerJob.cancel()
        }

    @Test
    fun `initializes with custom configurations from plugin context`() =
        runTest {
            val listenerJob = Job()
            val testScope = CoroutineScope(Dispatchers.Unconfined + listenerJob)
            val mockKord = mockk<Kord>(relaxed = true)
            every { mockKord.events } returns MutableSharedFlow()
            every { mockKord.coroutineContext } returns testScope.coroutineContext

            val mockRouting = mockk<Routing>(relaxed = true)
            val testDb = createTestDb()

            val env =
                EnvReader.of(
                    "GEMINI_API_KEY" to "custom-gemini-key",
                    "SUMMARY_EMOJI" to "⭐",
                    "GEMINI_MODEL" to "gemini-1.5-pro",
                    "YOUTUBE_API_KEY" to "custom-youtube-key",
                    "SUMMARY_MAX_HISTORY_MESSAGES" to "30",
                    "SUMMARY_INACTIVITY_GAP_MINUTES" to "20",
                    "SUMMARY_MAX_CHARACTER_BUDGET" to "15000",
                    "SUMMARY_MAX_LOOKBACK_MESSAGES" to "15",
                    "SUMMARY_MAX_IMAGES" to "5",
                )

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockRouting,
                    database = testDb,
                    env = env,
                )

            val plugin = SummarizerPlugin()
            plugin.initialize(context)

            assertEquals("⭐", plugin.configuredEmoji)
            assertEquals(30, plugin.maxHistoryMessages)
            assertEquals(20.minutes, plugin.inactivityGap)
            assertEquals(15000, plugin.maxCharacterBudget)
            assertEquals(15, plugin.maxLookbackMessages)
            assertEquals(5, plugin.maxImages)
            assertNotNull(plugin.summarizer)
            assertNotNull(plugin.summaryService)

            listenerJob.cancel()
        }

    @Test
    fun `throws ConfigValidationException when SUMMARY_MAX_LOOKBACK_MESSAGES is out of range or non numeric`() =
        runTest {
            val listenerJob = Job()
            val testScope = CoroutineScope(Dispatchers.Unconfined + listenerJob)
            val mockKord = mockk<Kord>(relaxed = true)
            every { mockKord.events } returns MutableSharedFlow()
            every { mockKord.coroutineContext } returns testScope.coroutineContext
            val mockRouting = mockk<Routing>(relaxed = true)
            val testDb = createTestDb()

            for (invalid in listOf("50", "0", "-5", "abc")) {
                val context =
                    DefaultPluginContext(
                        kord = mockKord,
                        routing = mockRouting,
                        database = testDb,
                        env = EnvReader.of("SUMMARY_MAX_LOOKBACK_MESSAGES" to invalid),
                    )
                val plugin = SummarizerPlugin()
                assertFailsWith<ConfigValidationException> {
                    plugin.initialize(context)
                }
            }

            listenerJob.cancel()
        }

    @Test
    fun `throws ConfigValidationException when summary numeric configurations are non positive, out of range, or non numeric`() =
        runTest {
            val listenerJob = Job()
            val testScope = CoroutineScope(Dispatchers.Unconfined + listenerJob)
            val mockKord = mockk<Kord>(relaxed = true)
            every { mockKord.events } returns MutableSharedFlow()
            every { mockKord.coroutineContext } returns testScope.coroutineContext
            val mockRouting = mockk<Routing>(relaxed = true)
            val testDb = createTestDb()

            val invalidCases =
                listOf(
                    "SUMMARY_MAX_IMAGES" to "-10",
                    "SUMMARY_MAX_IMAGES" to "0",
                    "SUMMARY_MAX_CHARACTER_BUDGET" to "-500",
                    "SUMMARY_MAX_CHARACTER_BUDGET" to "0",
                    "SUMMARY_INACTIVITY_GAP_MINUTES" to "-15",
                    "SUMMARY_INACTIVITY_GAP_MINUTES" to "not-a-number",
                    "SUMMARY_MAX_HISTORY_MESSAGES" to "999",
                    "SUMMARY_MAX_HISTORY_MESSAGES" to "0",
                    "SUMMARY_MAX_HISTORY_MESSAGES" to "abc",
                )

            for ((key, value) in invalidCases) {
                val context =
                    DefaultPluginContext(
                        kord = mockKord,
                        routing = mockRouting,
                        database = testDb,
                        env = EnvReader.of(key to value),
                    )
                val plugin = SummarizerPlugin()
                assertFailsWith<ConfigValidationException> {
                    plugin.initialize(context)
                }
            }

            listenerJob.cancel()
        }

    @Test
    fun `reaction event listener attaches via context kord and triggers summarization`() =
        runTest {
            val listenerJob = Job()
            val testScope = CoroutineScope(Dispatchers.Unconfined + listenerJob)
            val eventsFlow = MutableSharedFlow<Event>()
            val mockKord = mockk<Kord>(relaxed = true)
            every { mockKord.events } returns eventsFlow
            every { mockKord.coroutineContext } returns testScope.coroutineContext

            val mockRouting = mockk<Routing>(relaxed = true)
            val testDb = createTestDb()

            val mockSummarizer = mockk<GeminiSummarizer>(relaxed = true)
            var capturedInput: SummaryInput? = null
            coEvery { mockSummarizer.summarize(any<SummaryInput>(), any()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Plugin successfully summarized context")
            }

            var replySent: String? = null
            val plugin =
                SummarizerPlugin(
                    summarizerFactory = { mockSummarizer },
                    summaryServiceFactory = { summarizer, collector, emoji ->
                        SummaryService(
                            summarizer = summarizer,
                            collector = collector,
                            replySender = { _, content -> replySent = content },
                            configuredEmoji = emoji,
                        )
                    },
                )

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockRouting,
                    database = testDb,
                    env = EnvReader.of("SUMMARY_EMOJI" to "📝"),
                )

            plugin.initialize(context)

            // Setup mock Discord event via SummaryTestFixtures
            val targetMessage =
                SummaryTestFixtures.createMockMessage(
                    id = 8888L,
                    username = "authorUser",
                    content = "Important discussion to summarize via plugin",
                )
            val event =
                SummaryTestFixtures.createMockReactionEvent(
                    targetMessage = targetMessage,
                    emoji = ReactionEmoji.Unicode("📝"),
                )

            // Emit reaction event on Kord's event stream
            eventsFlow.emit(event)

            coVerify(exactly = 1) { mockSummarizer.summarize(any<SummaryInput>(), any()) }
            assertEquals("Important discussion to summarize via plugin", capturedInput?.content)
            assertEquals("TL;DR: Plugin successfully summarized context", replySent)

            listenerJob.cancel()
        }

    @Test
    fun `shutdown hook closes summarizer`() =
        runTest {
            val mockSummarizer = mockk<GeminiSummarizer>(relaxed = true)
            val plugin = SummarizerPlugin(summarizerFactory = { mockSummarizer })

            val context =
                DefaultPluginContext(
                    kord = mockk(relaxed = true),
                    routing = mockk(relaxed = true),
                    database = createTestDb(),
                )

            plugin.initialize(context)
            plugin.shutdown()

            verify(exactly = 1) { mockSummarizer.close() }
        }

    @Test
    fun `initialize injects aiTraceRecorder from PluginContext into GeminiSummarizer and SummaryService`(
        @org.junit.jupiter.api.io.TempDir tempDir: java.nio.file.Path,
    ) = runTest {
        val listenerJob = Job()
        val testScope = CoroutineScope(Dispatchers.Unconfined + listenerJob)
        val mockKord = mockk<Kord>(relaxed = true)
        every { mockKord.events } returns MutableSharedFlow()
        every { mockKord.coroutineContext } returns testScope.coroutineContext

        val recorder = luna.core.ai.JsonlAiTraceRecorder(traceDir = tempDir.resolve("traces"))
        try {
            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockk(relaxed = true),
                    database = createTestDb(),
                    env = EnvReader.of("GEMINI_API_KEY" to "test-key"),
                    aiTraceRecorder = recorder,
                )

            val plugin = SummarizerPlugin()
            plugin.initialize(context)

            kotlin.test.assertSame(recorder, plugin.summarizer?.traceRecorder)
            kotlin.test.assertSame(recorder, plugin.summaryService?.traceRecorder)
        } finally {
            recorder.flushAndClose()
            listenerJob.cancel()
        }
    }

    @Test
    fun `initialize resolves GEMINI_URL_CONTEXT_ENABLED and GEMINI_SEARCH_GROUNDING_ENABLED strictly and ignores legacy aliases`() =
        runTest {
            val listenerJob = Job()
            val testScope = CoroutineScope(Dispatchers.Unconfined + listenerJob)
            val mockKord = mockk<Kord>(relaxed = true)
            every { mockKord.events } returns MutableSharedFlow()
            every { mockKord.coroutineContext } returns testScope.coroutineContext
            val mockRouting = mockk<Routing>(relaxed = true)
            val testDb = createTestDb()

            suspend fun captureConfig(env: EnvReader): GeminiConfig {
                var captured: GeminiConfig? = null
                val plugin =
                    SummarizerPlugin(
                        summarizerFactory = { cfg ->
                            captured = cfg
                            mockk(relaxed = true)
                        },
                    )
                val context =
                    DefaultPluginContext(
                        kord = mockKord,
                        routing = mockRouting,
                        database = testDb,
                        env = env,
                    )
                plugin.initialize(context)
                return assertNotNull(captured)
            }

            assertTrue(captureConfig(EnvReader.of()).urlContextEnabled)
            assertTrue(captureConfig(EnvReader.of()).searchGroundingEnabled)
            assertFalse(captureConfig(EnvReader.of("GEMINI_URL_CONTEXT_ENABLED" to " false ")).urlContextEnabled)
            assertFalse(captureConfig(EnvReader.of("GEMINI_SEARCH_GROUNDING_ENABLED" to "FALSE")).searchGroundingEnabled)
            assertTrue(captureConfig(EnvReader.of("GEMINI_URL_CONTEXT_ENABLED" to "true")).urlContextEnabled)

            // Legacy dotted aliases are ignored
            assertTrue(captureConfig(EnvReader.of("gemini.urlContextEnabled" to "false")).urlContextEnabled)
            assertTrue(captureConfig(EnvReader.of("gemini.searchGroundingEnabled" to "false")).searchGroundingEnabled)

            // Negative boundary: non-boolean value fails fast
            assertFailsWith<ConfigValidationException> {
                captureConfig(EnvReader.of("GEMINI_URL_CONTEXT_ENABLED" to "invalid"))
            }
            assertFailsWith<ConfigValidationException> {
                captureConfig(EnvReader.of("GEMINI_SEARCH_GROUNDING_ENABLED" to "yes"))
            }

            listenerJob.cancel()
        }

    @Test
    fun `initialize normalizes GEMINI_MODEL and GEMINI_BASE_URL and ignores legacy summary aliases`() =
        runTest {
            val listenerJob = Job()
            val testScope = CoroutineScope(Dispatchers.Unconfined + listenerJob)
            val mockKord = mockk<Kord>(relaxed = true)
            every { mockKord.events } returns MutableSharedFlow()
            every { mockKord.coroutineContext } returns testScope.coroutineContext
            val mockRouting = mockk<Routing>(relaxed = true)
            val testDb = createTestDb()

            var captured: GeminiConfig? = null
            val plugin =
                SummarizerPlugin(
                    summarizerFactory = { cfg ->
                        captured = cfg
                        mockk(relaxed = true)
                    },
                )
            plugin.initialize(
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockRouting,
                    database = testDb,
                    env =
                        EnvReader.of(
                            "GEMINI_MODEL" to "  models/gemini-2.5-pro  ",
                            "GEMINI_BASE_URL" to "  https://proxy.example.com/v1beta/  ",
                            "summary.maxHistoryMessages" to "12",
                            "summary.emoji" to "🚀",
                        ),
                ),
            )

            val cfg = assertNotNull(captured)
            assertEquals("gemini-2.5-pro", cfg.model)
            assertEquals("https://proxy.example.com", cfg.baseUrl)
            assertEquals(ConversationSliceCollector.DEFAULT_MAX_HISTORY_MESSAGES, plugin.maxHistoryMessages)
            assertEquals(SummaryService.DEFAULT_EMOJI, plugin.configuredEmoji)

            listenerJob.cancel()
        }
}
