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
import luna.plugin.DefaultPluginContext
import luna.plugin.LunaPlugin
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SummarizerPluginTest {
    private fun createTestDb(): Database =
        Database.connect(
            "jdbc:h2:mem:summarizer_plugin_${UUID.randomUUID()};DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver",
        )

    @Test
    fun `summarizer plugin implements LunaPlugin with name summarizer`() {
        val plugin = SummarizerPlugin()
        assertEquals("summarizer", plugin.name)
    }

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
                    configMap = emptyMap(),
                )

            val plugin = SummarizerPlugin()
            plugin.initialize(context)

            assertEquals(SummaryService.DEFAULT_EMOJI, plugin.configuredEmoji)
            assertEquals(SummaryService.DEFAULT_MAX_HISTORY_MESSAGES, plugin.maxHistoryMessages)
            assertEquals(SummaryService.DEFAULT_INACTIVITY_GAP, plugin.inactivityGap)
            assertEquals(SummaryService.DEFAULT_MAX_CHARACTER_BUDGET, plugin.maxCharacterBudget)
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

            val configMap =
                mapOf(
                    "GEMINI_API_KEY" to "custom-gemini-key",
                    "SUMMARY_EMOJI" to "⭐",
                    "GEMINI_MODEL" to "gemini-1.5-pro",
                    "YOUTUBE_API_KEY" to "custom-youtube-key",
                    "SUMMARY_MAX_HISTORY_MESSAGES" to 30,
                    "SUMMARY_INACTIVITY_GAP_MINUTES" to 20L,
                    "SUMMARY_MAX_CHARACTER_BUDGET" to 15000,
                )

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockRouting,
                    database = testDb,
                    configMap = configMap,
                )

            val plugin = SummarizerPlugin()
            plugin.initialize(context)

            assertEquals("⭐", plugin.configuredEmoji)
            assertEquals(30, plugin.maxHistoryMessages)
            assertEquals(20.minutes, plugin.inactivityGap)
            assertEquals(15000, plugin.maxCharacterBudget)
            assertNotNull(plugin.summarizer)
            assertNotNull(plugin.summaryService)

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
            coEvery { mockSummarizer.summarize(any<SummaryInput>()) } answers {
                capturedInput = firstArg()
                SummaryResult.Success("TL;DR: Plugin successfully summarized context")
            }

            var replySent: String? = null
            val plugin =
                SummarizerPlugin(
                    summarizerFactory = { mockSummarizer },
                    summaryServiceFactory = { summarizer, emoji, historyLimit, gap, budget, peekResolver ->
                        SummaryService(
                            summarizer = summarizer,
                            peekResolver = peekResolver,
                            replySender = { _, content -> replySent = content },
                            configuredEmoji = emoji,
                            maxHistoryMessages = historyLimit,
                            inactivityGap = gap,
                            maxCharacterBudget = budget,
                        )
                    },
                )

            val context =
                DefaultPluginContext(
                    kord = mockKord,
                    routing = mockRouting,
                    database = testDb,
                    configMap = mapOf("SUMMARY_EMOJI" to "📝"),
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

            coVerify(exactly = 1) { mockSummarizer.summarize(any<SummaryInput>()) }
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
    fun `service loader discovers SummarizerPlugin on classpath`() {
        val loader = ServiceLoader.load(LunaPlugin::class.java)
        val plugins = loader.toList()
        assertTrue(
            plugins.any { it is SummarizerPlugin },
            "Expected SummarizerPlugin to be discoverable via ServiceLoader SPI",
        )
    }
}
