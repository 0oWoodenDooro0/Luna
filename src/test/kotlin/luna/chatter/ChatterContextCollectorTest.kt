package luna.chatter

import dev.kord.common.entity.Snowflake
import dev.socialpeek.model.PeekPost
import kotlinx.coroutines.test.runTest
import luna.core.LunaWebhookRegistry
import luna.core.SummaryMessageRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatterContextCollectorTest {
    private lateinit var filter: ChatterNoiseFilter

    @BeforeEach
    fun setUp() {
        LunaWebhookRegistry.clear()
        SummaryMessageRegistry.clear()
        filter = ChatterNoiseFilter()
    }

    @Test
    fun `collectDecisionContext keeps only last 3 filtered messages with embeds, image markers, and raw unstripped text`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val msg1 = createMockMessage(id = 1L, username = "Alice", content = "Earlier oldest message")
            val botNoise = createMockMessage(id = 2L, username = "MusicBot", content = "Now playing", isBot = true)
            val msg2 = createMockMessage(id = 3L, username = "Bob", content = "<@9999>  second  newest  valid")
            val embed = createMockEmbed(title = "Release Notes", description = "v2.0 highlights")
            val msg3 =
                createMockMessage(
                    id = 4L,
                    username = "Charlie",
                    content = "Check https://example.com/release",
                    embeds = listOf(embed),
                )
            val img = createMockAttachment("https://cdn.example.com/cat.png", "image/png", "cat.png")
            val msg4 =
                createMockMessage(
                    id = 5L,
                    username = "Dave",
                    content = "@Luna look at this",
                    attachments = setOf(img),
                )

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    decisionMessageCount = 3,
                    historyMessageCount = 15,
                    peekResolver = { null },
                )

            val result =
                collector.collectDecisionContext(
                    messages = listOf(msg1, botNoise, msg2, msg3, msg4),
                    kordSelfId = botSelfId,
                )

            assertFalse(result.transcript.contains("Earlier oldest message"))
            assertFalse(result.transcript.contains("MusicBot"))
            assertTrue(result.transcript.contains("[Bob]: <@9999>  second  newest  valid"))
            assertTrue(
                result.transcript.contains(
                    "[Charlie]: Check https://example.com/release [連結預覽: Release Notes - v2.0 highlights]",
                ),
            )
            assertTrue(result.transcript.contains("[Dave]: @Luna look at this [附帶圖片 1: cat.png]"))

            assertEquals(1, result.images.size)
            assertEquals("https://cdn.example.com/cat.png", result.images.first().url)
            assertEquals("image/png", result.images.first().mimeType)
        }

    @Test
    fun `collectDecisionContext preserves pure bot mention verbatim and falls back to Embed only when embed lacks preview text`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val pureMention = createMockMessage(id = 6L, username = "Alice", content = "<@!9999>")
            val embedWithPreview =
                createMockMessage(
                    id = 7L,
                    username = "Bob",
                    content = "",
                    embeds = listOf(createMockEmbed(title = "Preview Title", description = null)),
                )
            val embedWithoutPreview =
                createMockMessage(
                    id = 8L,
                    username = "Charlie",
                    content = "   ",
                    embeds = listOf(createMockEmbed(title = null, description = "   ")),
                )

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    decisionMessageCount = 3,
                    peekResolver = { null },
                )

            val result =
                collector.collectDecisionContext(
                    messages = listOf(pureMention, embedWithPreview, embedWithoutPreview),
                    kordSelfId = botSelfId,
                )

            assertTrue(result.transcript.contains("[Alice]: <@!9999>"))
            assertFalse(result.transcript.contains("你好"))
            assertTrue(result.transcript.contains("[Bob]: [連結預覽: Preview Title]"))
            assertFalse(result.transcript.contains("[Bob]: [Embed]"))
            assertTrue(result.transcript.contains("[Charlie]: [Embed]"))
        }

    @Test
    fun `collectDecisionContext caps at 3 newest valid images and discards oversized or unsupported attachments`() =
        runTest {
            val tenMbPlusOne = 10 * 1024 * 1024 + 1
            val img1 = createMockAttachment("https://cdn.example.com/1.png", "image/png", "1.png")
            val img2 = createMockAttachment("https://cdn.example.com/2.jpg", "image/jpeg", "2.jpg")
            val img3 = createMockAttachment("https://cdn.example.com/3.webp", "image/webp", "3.webp")
            val img4 = createMockAttachment("https://cdn.example.com/4.gif", "image/gif", "4.gif")
            val oversized = createMockAttachment("https://cdn.example.com/huge.png", "image/png", "huge.png", size = tenMbPlusOne)
            val spoofed =
                createMockAttachment("https://cdn.example.com/fake.png", "application/octet-stream", "fake.png", size = 1024)

            val msg1 = createMockMessage(id = 10L, username = "Alice", content = "Old pics", attachments = setOf(img1, img2))
            val msg2 = createMockMessage(id = 11L, username = "Bob", content = "", attachments = setOf(oversized, spoofed))
            val msg3 = createMockMessage(id = 12L, username = "Charlie", content = "New pics", attachments = setOf(img3, img4))

            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = { null },
                )

            val result = collector.collectDecisionContext(listOf(msg1, msg2, msg3))

            assertEquals(
                listOf("https://cdn.example.com/2.jpg", "https://cdn.example.com/3.webp", "https://cdn.example.com/4.gif"),
                result.images.map { it.url },
            )
            assertTrue(result.transcript.contains("[Alice]: Old pics [附帶圖片 1: 2.jpg]"))
            assertTrue(result.transcript.contains("[Bob]: [Attachment]"))
            assertFalse(result.transcript.contains("huge.png"))
            assertFalse(result.transcript.contains("fake.png"))
            assertTrue(result.transcript.contains("[Charlie]: New pics [附帶圖片 2: 3.webp] [附帶圖片 3: 4.gif]"))
        }

    @Test
    fun `collectDecisionContext never invokes SocialPeek resolver when URLs are present`() =
        runTest {
            var peekCalled = false
            val failingPeekResolver: suspend (String) -> PeekPost? = {
                peekCalled = true
                error("SocialPeek must not be called in decision stage")
            }

            val msg = createMockMessage(id = 20L, username = "Alice", content = "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    peekResolver = failingPeekResolver,
                )

            val result = collector.collectDecisionContext(listOf(msg))

            assertFalse(peekCalled)
            assertTrue(result.transcript.contains("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        }

    @Test
    fun `collectDecisionContext frames 15-message default window with Bot Identity, Recent Context, and Latest Message`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val olderDropped = createMockMessage(id = 100L, username = "OldUser", content = "Message outside 15 window")
            val botNoise = createMockMessage(id = 101L, username = "NoiseBot", content = "Bot noise", isBot = true)
            val recentMessages =
                (1..14).map { idx ->
                    createMockMessage(id = 200L + idx, username = "User$idx", content = "Context turn $idx")
                }
            val latestMessage = createMockMessage(id = 215L, username = "Trigger", content = "Should Luna reply?")

            val collector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null })
            val result =
                collector.collectDecisionContext(
                    messages = listOf(olderDropped, botNoise) + recentMessages + listOf(latestMessage),
                    kordSelfId = botSelfId,
                )

            val expectedTranscript =
                buildString {
                    appendLine("[Bot Identity: Luna | Discord Mention: <@9999>]")
                    appendLine()
                    appendLine("[Recent Context]")
                    (1..14).forEach { idx ->
                        appendLine("[User$idx]: Context turn $idx")
                    }
                    appendLine()
                    appendLine("[Latest Message]")
                    append("[Trigger]: Should Luna reply?")
                }

            assertFalse(result.transcript.contains("Message outside 15 window"))
            assertFalse(result.transcript.contains("NoiseBot"))
            assertEquals(expectedTranscript, result.transcript)
        }

    @Test
    fun `collectDecisionContext omits Recent Context for single message and omits Discord Mention when kordSelfId is null`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val singleMsg = createMockMessage(id = 301L, username = "Alice", content = "Anyone around?", kordUnavailable = true)
            val collector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null })

            // Single message with known selfId omits [Recent Context]
            val singleResult = collector.collectDecisionContext(listOf(singleMsg), kordSelfId = botSelfId)
            assertEquals(
                "[Bot Identity: Luna | Discord Mention: <@9999>]\n\n[Latest Message]\n[Alice]: Anyone around?",
                singleResult.transcript,
            )
            assertFalse(singleResult.transcript.contains("[Recent Context]"))

            // Null kordSelfId formats [Bot Identity: Luna] without Discord Mention
            val earlierMsg = createMockMessage(id = 300L, username = "Bob", content = "First line", kordUnavailable = true)
            val nullSelfResult = collector.collectDecisionContext(listOf(earlierMsg, singleMsg), kordSelfId = null)
            assertEquals(
                "[Bot Identity: Luna]\n\n[Recent Context]\n[Bob]: First line\n\n[Latest Message]\n[Alice]: Anyone around?",
                nullSelfResult.transcript,
            )
            assertFalse(nullSelfResult.transcript.contains("Discord Mention"))
        }

    @Test
    fun `collectDecisionContext preserves multiline messages and continuous image numbering across sections`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val img1 = createMockAttachment("https://cdn.example.com/first.png", "image/png", "first.png")
            val img2 = createMockAttachment("https://cdn.example.com/second.jpg", "image/jpeg", "second.jpg")
            val multilineRecent =
                createMockMessage(
                    id = 401L,
                    username = "Alice",
                    content = "Line one of recent\nLine two of recent",
                    attachments = setOf(img1),
                )
            val multilineLatest =
                createMockMessage(
                    id = 402L,
                    username = "Bob",
                    content = "Line one of latest\nLine two of latest",
                    attachments = setOf(img2),
                )

            val collector = ChatterContextCollector(noiseFilter = filter, peekResolver = { null })
            val result = collector.collectDecisionContext(listOf(multilineRecent, multilineLatest), kordSelfId = botSelfId)

            val expectedTranscript =
                "[Bot Identity: Luna | Discord Mention: <@9999>]\n\n" +
                    "[Recent Context]\n" +
                    "[Alice]: Line one of recent\nLine two of recent [附帶圖片 1: first.png]\n\n" +
                    "[Latest Message]\n" +
                    "[Bob]: Line one of latest\nLine two of latest [附帶圖片 2: second.jpg]"
            assertEquals(expectedTranscript, result.transcript)
            assertEquals(2, result.images.size)
        }

    @Test
    fun `collectDecisionContext resolves external reply references up to maxExternalReferences while keeping SocialPeek disabled`() =
        runTest {
            val botSelfId = Snowflake(9999L)
            val ext1 = createMockMessage(id = 5001L, username = "ExtAlice", content = "B".repeat(50))
            val ext2 = createMockMessage(id = 5002L, username = "ExtBob", content = "Earlier question")
            val ext3 = createMockMessage(id = 5003L, username = "ExtCharlie", content = "Target thread message")
            val ext4 = createMockMessage(id = 5004L, username = "ExtDave", content = "Fourth external beyond cap")

            val inWindowBase = createMockMessage(id = 5100L, username = "U0", content = "In-window base")
            val m1 = createMockMessage(id = 5101L, username = "U1", content = "Reply 1")
            val m2 = createMockMessage(id = 5102L, username = "U2", content = "Reply 2")
            val m3 = createMockMessage(id = 5103L, username = "U3", content = "Reply 3")
            val m4 = createMockMessage(id = 5104L, username = "U4", content = "Reply in window", referencedMessage = inWindowBase)
            val latest =
                createMockMessage(
                    id = 5105L,
                    username = "U5",
                    content = "Follow-up with link https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                )

            val externalLookup =
                mapOf(
                    m1.id to ext1,
                    m2.id to ext2,
                    m3.id to ext4,
                    latest.id to ext3,
                )

            var peekCalled = false
            val collector =
                ChatterContextCollector(
                    noiseFilter = filter,
                    maxExternalReferences = 3,
                    peekResolver = {
                        peekCalled = true
                        error("SocialPeek must stay disabled in collectDecisionContext")
                    },
                    referenceFetcher = { msg -> msg.referencedMessage ?: externalLookup[msg.id] },
                )

            val result =
                collector.collectDecisionContext(
                    messages = listOf(inWindowBase, m1, m2, m3, m4, latest),
                    kordSelfId = botSelfId,
                )

            val expectedExcerpt1 = "B".repeat(37) + "..."
            assertFalse(peekCalled)
            assertTrue(result.transcript.contains("[U1 回覆 ExtAlice「$expectedExcerpt1」]: Reply 1"))
            assertTrue(result.transcript.contains("[U2 回覆 ExtBob「Earlier question」]: Reply 2"))
            assertTrue(result.transcript.contains("[U3 回覆 ExtDave「Fourth external beyond cap」]: Reply 3"))
            assertTrue(result.transcript.contains("[U4]: Reply in window"))
            assertFalse(result.transcript.contains("回覆 U0"))
            // 4th unique external reference (ext3 on latest) is capped at maxExternalReferences = 3
            assertTrue(result.transcript.contains("[U5]: Follow-up with link https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            assertFalse(result.transcript.contains("回覆 ExtCharlie"))
        }
}
