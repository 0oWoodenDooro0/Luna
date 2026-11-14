package luna.chatter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.socialpeek.model.Author
import dev.socialpeek.model.Comment
import dev.socialpeek.model.PeekPost
import dev.socialpeek.model.Platform
import kotlinx.coroutines.test.runTest
import luna.core.LunaWebhookRegistry
import luna.core.SummaryMessageRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatterReplyContextTest {
    private lateinit var filter: ChatterNoiseFilter

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        SummaryMessageRegistry.clear()
        filter = ChatterNoiseFilter()
    }

    @Test
    fun `collectReplyContext keeps last 15 filtered messages and resolves up to 3 external reply references with 40 char excerpt`() =
        runTest {
            val ext1 = createMockMessage(id = 1001L, username = "ExtAlice", content = "A".repeat(60))
            val ext2 = createMockMessage(id = 1002L, username = "ExtBob", content = "Short external context")
            val ext3 = createMockMessage(id = 1003L, username = "ExtCharlie", content = "Third external msg")
            val ext4 = createMockMessage(id = 1004L, username = "ExtDave", content = "Fourth external msg beyond cap")

            val oldestDropped = createMockMessage(id = 1010L, username = "OldUser", content = "Too old beyond 15")
            val botNoise = createMockMessage(id = 1011L, username = "NoiseBot", content = "Ignore me", isBot = true)

            val windowMsgs =
                (1..15).map { idx ->
                    val id = 2000L + idx
                    when (idx) {
                        1 -> createMockMessage(id = id, username = "U1", content = "Reply to ext1", referencedMessage = ext1)
                        2 -> createMockMessage(id = id, username = "U2", content = "Reply to ext2", referencedMessage = ext2)
                        3 -> createMockMessage(id = id, username = "U3", content = "Reply to ext3", referencedMessage = ext3)
                        4 -> createMockMessage(id = id, username = "U4", content = "Reply to ext4", referencedMessage = ext4)
                        else -> createMockMessage(id = id, username = "U$idx", content = "Turn $idx")
                    }
                }
            val inWindowReply =
                createMockMessage(
                    id = 2016L,
                    username = "U16",
                    content = "Replying in window",
                    referencedMessage = windowMsgs[5],
                )
            // Keep 15 messages by dropping oldest U1? Wait: if we have 15 messages total in window, make in-window reply one of the 15
            val allMessages =
                listOf(oldestDropped, botNoise) +
                    windowMsgs.dropLast(1) +
                    listOf(inWindowReply)

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    historyMessageCount = 15,
                    peekResolver = { null },
                )

            val result = collector.collectReplyContext(allMessages)

            assertFalse(result.transcript.contains("Too old beyond 15"))
            assertFalse(result.transcript.contains("NoiseBot"))
            val expectedExcerpt = "A".repeat(37) + "..."
            assertTrue(result.transcript.contains("[U1 回覆 ExtAlice「$expectedExcerpt」]: Reply to ext1"))
            assertTrue(result.transcript.contains("[U2 回覆 ExtBob「Short external context」]: Reply to ext2"))
            assertTrue(result.transcript.contains("[U3 回覆 ExtCharlie「Third external msg」]: Reply to ext3"))
            assertTrue(result.transcript.contains("[U4]: Reply to ext4"))
            assertFalse(result.transcript.contains("回覆 ExtDave"))
            assertTrue(result.transcript.contains("[U16]: Replying in window"))
            assertFalse(result.transcript.contains("回覆 U6"))
        }

    @Test
    fun `collectReplyContext retains up to maxImages newest valid images and discards oversized or unsupported attachments`() =
        runTest {
            val tenMbPlusOne = 10 * 1024 * 1024 + 1
            val validAttachments =
                (1..12).map { idx ->
                    createMockAttachment("https://cdn.example.com/$idx.png", "image/png", "$idx.png")
                }
            val oversized = createMockAttachment("https://cdn.example.com/huge.jpg", "image/jpeg", "huge.jpg", size = tenMbPlusOne)
            val svgUnsupported = createMockAttachment("https://cdn.example.com/vec.svg", "image/svg+xml", "vec.svg", size = 1024)

            val messages =
                validAttachments.mapIndexed { idx, att ->
                    createMockMessage(id = 3000L + idx, username = "User$idx", content = "img $idx", attachments = setOf(att))
                } +
                    listOf(
                        createMockMessage(id = 3050L, username = "BadUser", content = "bad", attachments = setOf(oversized, svgUnsupported)),
                    )

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    maxImages = 10,
                    peekResolver = { null },
                )

            val result = collector.collectReplyContext(messages)

            assertEquals(10, result.imageAttachments.size)
            assertEquals(
                (3..12).map { "https://cdn.example.com/$it.png" },
                result.imageAttachments.map { it.url },
            )
            assertFalse(result.transcript.contains("huge.jpg"))
            assertFalse(result.transcript.contains("vec.svg"))
            assertTrue(result.transcript.contains("[附帶圖片 1: 3.png]"))
            assertTrue(result.transcript.contains("[附帶圖片 10: 12.png]"))
        }

    @Test
    fun `collectReplyContext resolves only latest primaryUrl via SocialPeek and enriches transcript`() =
        runTest {
            val resolvedUrls = mutableListOf<String>()
            val peekPost =
                PeekPost(
                    platform = Platform.THREADS,
                    id = "post123",
                    originalUrl = "https://www.threads.net/@alice/post/123?x=1",
                    cleanUrl = "https://www.threads.net/@alice/post/123",
                    author = Author(username = "alice"),
                    title = "Kotlin Multiplatform Updates",
                    content = "Compose Multiplatform for iOS is now stable!",
                    comments =
                        listOf(
                            Comment(author = "dev1", content = "Finally stable!"),
                            Comment(author = "dev2", content = "Awesome news"),
                        ),
                )

            val msg1 = createMockMessage(id = 4001L, username = "Bob", content = "Earlier link https://example.com/old-article")
            val msg2 =
                createMockMessage(
                    id = 4002L,
                    username = "Charlie",
                    content = "Check this thread https://www.threads.net/@alice/post/123?x=1",
                )

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { url ->
                        resolvedUrls.add(url)
                        peekPost
                    },
                )

            val result = collector.collectReplyContext(listOf(msg1, msg2))

            assertEquals(listOf("https://www.threads.net/@alice/post/123?x=1"), resolvedUrls)
            assertEquals("https://www.threads.net/@alice/post/123?x=1", result.primaryUrl)
            assertEquals("https://www.threads.net/@alice/post/123", result.url)
            assertEquals("Kotlin Multiplatform Updates", result.title)
            assertEquals("Compose Multiplatform for iOS is now stable!", result.postContent)
            assertEquals(listOf("Finally stable!", "Awesome news"), result.comments)
            assertNull(result.videoUri)
            assertNull(result.videoDurationSeconds)

            assertTrue(result.transcript.contains("[Bob]: Earlier link https://example.com/old-article"))
            assertTrue(result.transcript.contains("[Charlie]: Check this thread https://www.threads.net/@alice/post/123?x=1"))
            assertTrue(result.transcript.contains("Kotlin Multiplatform Updates"))
            assertTrue(result.transcript.contains("Compose Multiplatform for iOS is now stable!"))
            assertTrue(result.transcript.contains("Finally stable!"))
        }

    @Test
    fun `collectReplyContext captures YouTube videoUri and durationSeconds from PeekPost`() =
        runTest {
            val ytUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            val ytPost =
                PeekPost(
                    platform = Platform.YOUTUBE,
                    id = "dQw4w9WgXcQ",
                    originalUrl = ytUrl,
                    cleanUrl = ytUrl,
                    author = Author(username = "Creator"),
                    title = "Kotlin Coroutines Deep Dive",
                    content = "Video description about structured concurrency",
                    comments = listOf(Comment(author = "viewer", content = "Great explanation")),
                    rawData = mapOf("durationSeconds" to "420"),
                )

            val msg = createMockMessage(id = 4010L, username = "Alice", content = "Watch $ytUrl")
            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { ytPost },
                )

            val result = collector.collectReplyContext(listOf(msg))

            assertEquals(ytUrl, result.primaryUrl)
            assertEquals(ytUrl, result.url)
            assertEquals(ytUrl, result.videoUri)
            assertEquals(420L, result.videoDurationSeconds)
            assertEquals("Kotlin Coroutines Deep Dive", result.title)
            assertEquals("Video description about structured concurrency", result.postContent)
            assertEquals(listOf("Great explanation"), result.comments)
            assertTrue(result.transcript.contains("Kotlin Coroutines Deep Dive"))
            assertTrue(result.transcript.contains("Video description about structured concurrency"))
        }

    @Test
    fun `collectReplyContext logs warning and degrades to Discord embed transcript when SocialPeek fails or returns null for social URL`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterContextCollector::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val socialUrl = "https://www.threads.net/@alice/post/broken"
                val embed = createMockEmbed(title = "Fallback Embed Title", description = "Fallback embed snippet")
                val msg =
                    createMockMessage(
                        id = 5001L,
                        username = "Alice",
                        content = "Read $socialUrl",
                        embeds = listOf(embed),
                    )

                // Case 1: peekResolver throws exception
                val throwingCollector =
                    ChatterContextCollector(
                        noiseFilter = filter,
                        peekResolver = { throw IllegalStateException("HTTP 503 upstream failure") },
                    )
                val degradedOnThrow = throwingCollector.collectReplyContext(listOf(msg))

                assertEquals(socialUrl, degradedOnThrow.primaryUrl)
                assertNull(degradedOnThrow.title)
                assertNull(degradedOnThrow.postContent)
                assertTrue(degradedOnThrow.comments.isEmpty())
                assertTrue(degradedOnThrow.webUrls.isEmpty())
                assertTrue(degradedOnThrow.transcript.contains("[Alice]: Read $socialUrl"))
                assertTrue(degradedOnThrow.transcript.contains("[連結預覽: Fallback Embed Title - Fallback embed snippet]"))
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains(socialUrl)
                    },
                )

                listAppender.list.clear()

                // Case 2: peekResolver returns null
                val nullCollector =
                    ChatterContextCollector(
                        noiseFilter = filter,
                        peekResolver = { null },
                    )
                val degradedOnNull = nullCollector.collectReplyContext(listOf(msg))

                assertEquals(socialUrl, degradedOnNull.primaryUrl)
                assertNull(degradedOnNull.postContent)
                assertTrue(degradedOnNull.webUrls.isEmpty())
                assertTrue(degradedOnNull.transcript.contains("[連結預覽: Fallback Embed Title - Fallback embed snippet]"))
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains(socialUrl)
                    },
                )
            } finally {
                logger.detachAppender(listAppender)
                listAppender.stop()
            }
        }

    @Test
    fun `collectReplyContext skips peekResolver for general web URLs, preserves embeds, caps at 20, and excludes Discord CDN`() =
        runTest {
            var peekCalled = false
            val embed = createMockEmbed(title = "Kotlin Docs", description = "Coroutines guide")
            val cdnAttachment =
                createMockAttachment("https://cdn.discordapp.com/attachments/1/2/shot.png", "image/png", "shot.png")

            val msgs =
                buildList {
                    add(
                        createMockMessage(
                            id = 6001L,
                            username = "Alice",
                            content = "Check https://kotlinlang.org/docs/coroutines.html and https://kotlinlang.org/docs/coroutines.html/",
                            embeds = listOf(embed),
                            attachments = setOf(cdnAttachment),
                        ),
                    )
                    // Add 21 more unique web URLs across messages in the 15-message window
                    for (idx in 1..11) {
                        add(
                            createMockMessage(
                                id = 6010L + idx,
                                username = "User$idx",
                                content = "Link A https://example.com/page-${idx * 2 - 1} and B https://example.com/page-${idx * 2}",
                            ),
                        )
                    }
                    add(
                        createMockMessage(
                            id = 6099L,
                            username = "Dave",
                            content = "Image link https://cdn.discordapp.com/attachments/1/2/shot.png",
                        ),
                    )
                }

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = {
                        peekCalled = true
                        error("peekResolver must not be invoked for general public web URLs")
                    },
                )

            val prepared = collector.collectReplyContext(msgs)
            val input = prepared.toChatterInput(isDirectMention = true)

            assertFalse(peekCalled, "General public web URLs must never invoke peekResolver")
            assertNull(prepared.primaryUrl)
            assertEquals(20, prepared.webUrls.size)
            assertEquals("https://kotlinlang.org/docs/coroutines.html", prepared.webUrls.first())
            assertFalse(prepared.webUrls.any { it.contains("cdn.discordapp.com") })
            assertTrue(prepared.transcript.contains("[連結預覽: Kotlin Docs - Coroutines guide]"))
            assertTrue(prepared.transcript.contains("https://kotlinlang.org/docs/coroutines.html"))

            assertEquals(prepared.webUrls, input.webUrls)
            assertTrue(input.isDirectMention)
        }

    @Test
    fun `collectReplyContext resolves social URL via peekResolver while preserving webUrls in same window`() =
        runTest {
            val resolvedUrls = mutableListOf<String>()
            val socialUrl = "https://x.com/kotlin/status/12345"
            val webUrl = "https://blog.jetbrains.com/kotlin/2026/release/"
            val peekPost =
                PeekPost(
                    platform = Platform.X,
                    id = "12345",
                    originalUrl = socialUrl,
                    cleanUrl = socialUrl,
                    author = Author(username = "kotlin"),
                    title = "Kotlin Post",
                    content = "New release highlights",
                )

            val msg1 = createMockMessage(id = 6101L, username = "Alice", content = "Release blog: $webUrl")
            val msg2 = createMockMessage(id = 6102L, username = "Bob", content = "Tweet: $socialUrl")

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { url ->
                        resolvedUrls.add(url)
                        peekPost
                    },
                )

            val prepared = collector.collectReplyContext(listOf(msg1, msg2))
            val input = prepared.toChatterInput()

            assertEquals(listOf(socialUrl), resolvedUrls)
            assertEquals(listOf(webUrl), prepared.webUrls)
            assertEquals(listOf(webUrl), input.webUrls)
            assertEquals("Kotlin Post", prepared.title)
            assertTrue(prepared.transcript.contains(webUrl))
            assertTrue(prepared.transcript.contains("New release highlights"))
        }

    @Test
    fun `collectReplyContext falls back to regex-detected videoUri with warning log when YouTube resolution fails or returns null`() =
        runTest {
            val logger = LoggerFactory.getLogger(ChatterContextCollector::class.java) as Logger
            val listAppender = ListAppender<ILoggingEvent>()
            listAppender.start()
            logger.addAppender(listAppender)

            try {
                val ytUrl = "https://youtu.be/dQw4w9WgXcQ"
                val webUrl = "https://kotlinlang.org/docs/home.html"
                val msg1 = createMockMessage(id = 6201L, username = "Alice", content = "Docs: $webUrl")
                val msg2 = createMockMessage(id = 6202L, username = "Bob", content = "Video: $ytUrl")

                // Case 1: YouTube resolution returns null (e.g. unconfigured YouTube Data API)
                val nullCollector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null })
                val nullPrepared = nullCollector.collectReplyContext(listOf(msg1, msg2))

                assertEquals(ytUrl, nullPrepared.primaryUrl)
                assertEquals(ytUrl, nullPrepared.url)
                assertEquals(ytUrl, nullPrepared.videoUri)
                assertNull(nullPrepared.videoDurationSeconds)
                assertEquals(listOf(webUrl), nullPrepared.webUrls)
                assertTrue(
                    listAppender.list.any {
                        it.level == Level.WARN && it.formattedMessage.contains(ytUrl)
                    },
                )

                listAppender.list.clear()

                // Case 2: YouTube resolution throws exception
                val throwingCollector =
                    ChatterContextCollector(
                        noiseFilter = filter,
                        peekResolver = { throw IllegalStateException("YouTube Data API 403") },
                    )
                val throwPrepared = throwingCollector.collectReplyContext(listOf(msg1, msg2))

                assertEquals(ytUrl, throwPrepared.videoUri)
                assertNull(throwPrepared.videoDurationSeconds)
                assertEquals(listOf(webUrl), throwPrepared.webUrls)
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

    @Test
    fun `default ChatterContextCollector uses SocialPeekFactory without GenericWebResolver for general web URLs`() =
        runTest {
            val webUrl = "https://127.0.0.1:1/no-generic-scrape"
            val msg = createMockMessage(id = 6301L, username = "Alice", content = "Check $webUrl")

            val defaultCollector = ChatterContextCollector(noiseFilter = filter)
            val prepared = defaultCollector.collectReplyContext(listOf(msg))

            assertNull(prepared.primaryUrl)
            assertNull(prepared.postContent)
            assertEquals(listOf(webUrl), prepared.webUrls)
        }
}
