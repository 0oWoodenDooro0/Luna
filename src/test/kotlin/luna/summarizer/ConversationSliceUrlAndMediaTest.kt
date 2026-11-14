package luna.summarizer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import dev.socialpeek.model.Author
import dev.socialpeek.model.Comment
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import kotlinx.coroutines.test.runTest
import luna.core.ai.VideoProcessingMode
import luna.summarizer.ConversationSliceTestSupport.createMockAttachment
import luna.summarizer.ConversationSliceTestSupport.createMockMessage
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ConversationSliceUrlAndMediaTest {
    @BeforeEach
    fun setUp() {
        luna.core.LunaWebhookRegistry.clear()
        luna.core.SummaryMessageRegistry.clear()
    }

    @Test
    fun `collectSlice flags durationExceeded when YouTube video exceeds 2 hour limit`() =
        runTest {
            val targetMsg = createMockMessage(301L, "Alice", "Watch this https://www.youtube.com/watch?v=dQw4w9WgXcQ")

            val fakePost =
                PeekPost(
                    id = "dQw4w9WgXcQ",
                    originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    platform = Platform.YOUTUBE,
                    title = "Super Long Stream",
                    content = "Stream description",
                    author = Author(username = "Creator"),
                    rawData = mapOf("durationSeconds" to "7500"),
                )

            val collector =
                ConversationSliceCollector(
                    peekResolver = { fakePost },
                    historyFetcher = { _, _ -> emptyList() },
                )

            val slice = collector.collectSlice(targetMsg)
            assertNotNull(slice)
            assertTrue(slice.durationExceeded)
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", slice.primaryUrl)
        }

    @Test
    fun `collectSlice resolves most recent SocialPeek URL via peekResolver and degrades cleanly when unresolved`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val msg1 = createMockMessage(9101L, "Alice", "Old post: https://x.com/alice/status/111", timestamp = baseTime)
            val msg2 = createMockMessage(9102L, "Bob", "New post: https://x.com/bob/status/222", timestamp = baseTime + 1.minutes)
            val targetMsg = createMockMessage(9103L, "Charlie", "Thoughts?", timestamp = baseTime + 2.minutes)

            var resolvedUrl: String? = null
            val enrichedSlice =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                    peekResolver = { url ->
                        resolvedUrl = url
                        PeekPost(
                            platform = Platform.X,
                            id = "222",
                            originalUrl = url,
                            author = Author(username = "bob"),
                            title = "Second Post Title",
                            content = "Resolved social post body.",
                            comments = listOf(Comment(author = "reader1", content = "Clear explanation")),
                        )
                    },
                ).collectSlice(targetMsg)

            assertNotNull(enrichedSlice)
            assertEquals("https://x.com/bob/status/222", resolvedUrl)
            assertEquals("https://x.com/bob/status/222", enrichedSlice.summaryInput.url)
            assertEquals("Second Post Title", enrichedSlice.summaryInput.title)
            assertEquals(listOf("Clear explanation"), enrichedSlice.summaryInput.comments)
            assertTrue(enrichedSlice.summaryInput.content.contains("Resolved social post body."))
            assertTrue(enrichedSlice.summaryInput.webUrls.isEmpty())

            val fallbackSlice =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg2) },
                    peekResolver = { null },
                ).collectSlice(targetMsg)

            assertNotNull(fallbackSlice)
            assertEquals("https://x.com/bob/status/222", fallbackSlice.summaryInput.url)
            assertNull(fallbackSlice.summaryInput.title)
            assertEquals(emptyList(), fallbackSlice.summaryInput.comments)
            assertTrue(fallbackSlice.summaryInput.content.contains("[Bob]: New post: https://x.com/bob/status/222"))
            assertNotNull(fallbackSlice.lookbackProvider)
        }

    @Test
    fun `collectSlice skips peekResolver for general public web URLs populates deduplicated webUrls and preserves lookbackProvider`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val embed = SummaryTestFixtures.createMockEmbed(title = "OpenGraph Title", description = "OpenGraph snippet")
            val msg1 =
                createMockMessage(
                    9201L,
                    "Alice",
                    "Read https://kotlinlang.org/docs/whatsnew20.html",
                    timestamp = baseTime,
                    embeds = listOf(embed),
                )
            val msg2 =
                createMockMessage(
                    9202L,
                    "Bob",
                    "Also check https://docs.example.org/guide and https://kotlinlang.org/docs/whatsnew20.html/",
                    timestamp = baseTime + 1.minutes,
                )
            val targetMsg = createMockMessage(9203L, "Charlie", "Can someone summarize these docs?", timestamp = baseTime + 2.minutes)

            var peekCalled = false
            val slice =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                    peekResolver = {
                        peekCalled = true
                        null
                    },
                ).collectSlice(targetMsg)

            assertNotNull(slice)
            assertFalse(peekCalled, "peekResolver must not be invoked for general public web URLs")
            assertEquals(
                listOf(
                    "https://kotlinlang.org/docs/whatsnew20.html",
                    "https://docs.example.org/guide",
                ),
                slice.summaryInput.webUrls,
            )
            assertEquals("https://docs.example.org/guide", slice.primaryUrl)
            assertEquals("https://docs.example.org/guide", slice.summaryInput.url)
            assertTrue(slice.summaryInput.content.contains("https://kotlinlang.org/docs/whatsnew20.html"))
            assertTrue(slice.summaryInput.content.contains("https://docs.example.org/guide"))
            assertNotNull(slice.lookbackProvider, "lookbackProvider must be preserved for web URL slices")
        }

    @Test
    fun `collectSlice excludes Discord CDN URLs from webUrls and enforces 20 URL cap`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val cdnAttachment = createMockAttachment("https://cdn.discordapp.com/attachments/1/2/diagram.png", "image/png", "diagram.png")
            val urlsPart1 = (1..12).joinToString(" ") { "https://site$it.example.com/article" }
            val urlsPart2 = (13..24).joinToString(" ") { "https://site$it.example.com/article" }

            val msg1 =
                createMockMessage(
                    9301L,
                    "Alice",
                    "CDN link https://cdn.discordapp.com/attachments/1/2/diagram.png and https://media.discordapp.net/attachments/9/8/pic.webp $urlsPart1",
                    timestamp = baseTime,
                    attachments = setOf(cdnAttachment),
                )
            val targetMsg = createMockMessage(9302L, "Bob", "More links: $urlsPart2", timestamp = baseTime + 1.minutes)

            var peekCalled = false
            val slice =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1) },
                    peekResolver = {
                        peekCalled = true
                        null
                    },
                ).collectSlice(targetMsg)

            assertNotNull(slice)
            assertFalse(peekCalled)
            assertEquals(20, slice.summaryInput.webUrls.size)
            assertEquals("https://site1.example.com/article", slice.summaryInput.webUrls.first())
            assertEquals("https://site20.example.com/article", slice.summaryInput.webUrls.last())
            assertFalse(slice.summaryInput.webUrls.any { it.contains("discordapp") })
            assertTrue(slice.summaryInput.originalUrls.size >= 24)
            assertTrue(slice.summaryInput.originalUrls.contains("https://site24.example.com/article"))
        }

    @Test
    fun `collectSlice resolves SocialPeek URL alongside web URLs in same slice without losing webUrls`() =
        runTest {
            val baseTime = Instant.parse("2026-11-06T12:00:00Z")
            val msg1 = createMockMessage(9401L, "Alice", "Reference doc: https://docs.example.org/spec", timestamp = baseTime)
            val msg2 = createMockMessage(9402L, "Bob", "Video talk: https://www.youtube.com/watch?v=mixed123", timestamp = baseTime + 1.minutes)
            val targetMsg = createMockMessage(9403L, "Charlie", "Summarize both", timestamp = baseTime + 2.minutes)

            val peekedUrls = mutableListOf<String>()
            val slice =
                ConversationSliceCollector(
                    historyFetcher = { _, _ -> listOf(msg1, msg2) },
                    peekResolver = { url ->
                        peekedUrls.add(url)
                        PeekPost(
                            platform = Platform.YOUTUBE,
                            id = "mixed123",
                            originalUrl = url,
                            cleanUrl = url,
                            author = Author(username = "Speaker"),
                            title = "Mixed Talk",
                            content = "00:00 Intro",
                            rawData = mapOf("durationSeconds" to "120"),
                        )
                    },
                ).collectSlice(targetMsg)

            assertNotNull(slice)
            assertEquals(listOf("https://www.youtube.com/watch?v=mixed123"), peekedUrls)
            assertEquals("https://www.youtube.com/watch?v=mixed123", slice.summaryInput.videoUri)
            assertEquals(listOf("https://docs.example.org/spec"), slice.summaryInput.webUrls)
        }

    @Test
    fun `default ConversationSliceCollector uses SocialPeekFactory without GenericWebResolver for general web URLs`() =
        runTest {
            var hitCount = 0
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/article") { exchange ->
                hitCount++
                val body = "<html><head><title>Local Article</title></head><body>Hello</body></html>".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val webUrl = "http://127.0.0.1:${server.address.port}/article"
                val targetMsg = createMockMessage(9501L, "Alice", "Check $webUrl")

                val defaultCollector =
                    ConversationSliceCollector(
                        historyFetcher = { _, _ -> emptyList() },
                        forwardFetcher = { _, _ -> emptyList() },
                    )

                assertNull(defaultCollector.peekResolver(webUrl), "Default peekResolver must exclude GenericWebResolver")
                assertEquals(0, hitCount, "Default peekResolver must not scrape general web URLs")

                val slice = defaultCollector.collectSlice(targetMsg)
                assertNotNull(slice)
                assertEquals(0, hitCount)
                assertEquals(webUrl, slice.primaryUrl)
                assertEquals(listOf(webUrl), slice.summaryInput.webUrls)
                assertNull(slice.summaryInput.title)
                assertTrue(slice.summaryInput.comments.isEmpty())
                assertNotNull(slice.lookbackProvider)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `collectSlice enriches YouTube metadata and falls back to regex videoUri when peekResolver fails`() =
        runTest {
            val logger = LoggerFactory.getLogger(ConversationSliceCollector::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val baseTime = Instant.parse("2026-11-06T12:00:00Z")
                val ytUrl = "https://youtu.be/dQw4w9WgXcQ"
                val webUrl = "https://kotlinlang.org/docs/home.html"
                val msg1 = createMockMessage(9601L, "Alice", "Docs: $webUrl", timestamp = baseTime)
                val targetMsg = createMockMessage(9602L, "Bob", "Watch: $ytUrl", timestamp = baseTime + 1.minutes)

                // Case 1: YouTubeResolver succeeds via peekResolver
                val successCollector =
                    ConversationSliceCollector(
                        historyFetcher = { _, _ -> listOf(msg1) },
                        forwardFetcher = { _, _ -> emptyList() },
                        peekResolver = { url ->
                            PeekPost(
                                platform = Platform.YOUTUBE,
                                id = "dQw4w9WgXcQ",
                                originalUrl = url,
                                cleanUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                                author = Author(username = "Creator"),
                                title = "Demo Talk",
                                content = "Creator description body",
                                comments = listOf(Comment(author = "viewer1", content = "Helpful breakdown")),
                                rawData = mapOf("durationSeconds" to "240"),
                            )
                        },
                    )
                val enrichedSlice = successCollector.collectSlice(targetMsg)
                assertNotNull(enrichedSlice)
                assertFalse(enrichedSlice.durationExceeded)
                assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", enrichedSlice.summaryInput.videoUri)
                assertEquals(240L, enrichedSlice.summaryInput.videoDurationSeconds)
                assertTrue(enrichedSlice.summaryInput.content.contains("Creator description body"))
                assertEquals(listOf("Helpful breakdown"), enrichedSlice.summaryInput.comments)
                assertEquals(listOf(webUrl), enrichedSlice.summaryInput.webUrls)

                // Case 2: peekResolver returns null (e.g. missing YouTube Data API key)
                val nullCollector =
                    ConversationSliceCollector(
                        historyFetcher = { _, _ -> listOf(msg1) },
                        forwardFetcher = { _, _ -> emptyList() },
                        peekResolver = { null },
                    )
                val nullSlice = nullCollector.collectSlice(targetMsg)
                assertNotNull(nullSlice)
                assertFalse(nullSlice.durationExceeded)
                assertEquals(ytUrl, nullSlice.primaryUrl)
                assertEquals(ytUrl, nullSlice.summaryInput.videoUri)
                assertNull(nullSlice.summaryInput.videoDurationSeconds)
                assertEquals(
                    VideoProcessingMode.STATIC,
                    GeminiPromptBuilder.resolveProcessingMode(nullSlice.summaryInput.videoDurationSeconds),
                )
                assertEquals(listOf(webUrl), nullSlice.summaryInput.webUrls)
                assertNotNull(nullSlice.lookbackProvider)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains(ytUrl)
                    },
                )

                listAppender.list.clear()

                // Case 3: peekResolver throws exception
                val throwingCollector =
                    ConversationSliceCollector(
                        historyFetcher = { _, _ -> listOf(msg1) },
                        forwardFetcher = { _, _ -> emptyList() },
                        peekResolver = { throw IllegalStateException("YouTube Data API 403") },
                    )
                val throwSlice = throwingCollector.collectSlice(targetMsg)
                assertNotNull(throwSlice)
                assertFalse(throwSlice.durationExceeded)
                assertEquals(ytUrl, throwSlice.primaryUrl)
                assertEquals(ytUrl, throwSlice.summaryInput.videoUri)
                assertNull(throwSlice.summaryInput.videoDurationSeconds)
                assertEquals(
                    VideoProcessingMode.STATIC,
                    GeminiPromptBuilder.resolveProcessingMode(throwSlice.summaryInput.videoDurationSeconds),
                )
                assertEquals(listOf(webUrl), throwSlice.summaryInput.webUrls)
                assertNotNull(throwSlice.lookbackProvider)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains(ytUrl)
                    },
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }
}
