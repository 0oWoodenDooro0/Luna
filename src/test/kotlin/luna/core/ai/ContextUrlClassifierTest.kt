package luna.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextUrlClassifierTest {
    @Test
    fun `extracts and classifies general public web URLs in chronological order while trimming trailing punctuation`() {
        val messages =
            listOf(
                "Check out https://example.com/article. Also see (https://news.ycombinator.com/item?id=12345)!",
                "Here is Wikipedia: https://en.wikipedia.org/wiki/Kotlin_(programming_language), and <https://blog.google/technology/ai/>",
            )

        val result = ContextUrlClassifier.classify(messages)

        val expected =
            listOf(
                "https://example.com/article",
                "https://news.ycombinator.com/item?id=12345",
                "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
                "https://blog.google/technology/ai/",
            )
        assertEquals(expected, result.webUrls)
        assertEquals(expected, result.allOriginalUrls)
        assertEquals(emptyList(), result.socialUrls)
        assertEquals(emptyList(), result.youtubeUrls)
        assertEquals(emptyList(), result.discordCdnUrls)
    }

    @Test
    fun `classifies SocialPeek supported social links and excludes them from webUrls`() {
        val socialMessages =
            listOf(
                "FB post https://www.facebook.com/Meta/posts/10158000000000000",
                "FB watch https://fb.watch/abc123/",
                "IG https://www.instagram.com/p/C-example123/",
                "Threads net https://www.threads.net/@zuck/post/C-example123 and com https://www.threads.com/@zuck/post/C-example456",
                "X https://x.com/Kotlin/status/1800000000000000000 and Twitter https://twitter.com/Kotlin/status/1800000000000000001",
                "Bilibili https://www.bilibili.com/video/BV1xx411c7mD and short https://b23.tv/abc123",
                "Reddit https://www.reddit.com/r/Kotlin/comments/12345/discussion/ and short https://redd.it/12345",
            )

        val result = ContextUrlClassifier.classify(socialMessages)

        val expectedSocial =
            listOf(
                "https://www.facebook.com/Meta/posts/10158000000000000",
                "https://fb.watch/abc123/",
                "https://www.instagram.com/p/C-example123/",
                "https://www.threads.net/@zuck/post/C-example123",
                "https://www.threads.com/@zuck/post/C-example456",
                "https://x.com/Kotlin/status/1800000000000000000",
                "https://twitter.com/Kotlin/status/1800000000000000001",
                "https://www.bilibili.com/video/BV1xx411c7mD",
                "https://b23.tv/abc123",
                "https://www.reddit.com/r/Kotlin/comments/12345/discussion/",
                "https://redd.it/12345",
            )
        assertEquals(expectedSocial, result.socialUrls)
        assertEquals(emptyList(), result.youtubeUrls)
        assertEquals(emptyList(), result.webUrls)
        assertEquals(expectedSocial, result.allOriginalUrls)
    }

    @Test
    fun `classifies YouTube links including music subdomain and excludes them from webUrls and socialUrls`() {
        val transcript =
            """
            https://www.youtube.com/watch?v=dQw4w9WgXcQ
            https://youtu.be/dQw4w9WgXcQ?t=30
            https://m.youtube.com/shorts/abcdefghijk
            https://www.youtube.com/live/lmnopqrstuv
            https://music.youtube.com/watch?v=wxyz1234567
            """.trimIndent()

        val result = ContextUrlClassifier.classify(transcript)

        val expectedYouTube =
            listOf(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ?t=30",
                "https://m.youtube.com/shorts/abcdefghijk",
                "https://www.youtube.com/live/lmnopqrstuv",
                "https://music.youtube.com/watch?v=wxyz1234567",
            )
        assertEquals(expectedYouTube, result.youtubeUrls)
        assertEquals(emptyList(), result.socialUrls)
        assertEquals(emptyList(), result.webUrls)
        assertEquals(expectedYouTube, result.allOriginalUrls)
    }

    @Test
    fun `separates mixed social YouTube and web URLs while preserving chronological order`() {
        val messages =
            listOf(
                "First article: https://example.com/first",
                "Watch this: https://www.youtube.com/watch?v=dQw4w9WgXcQ and read https://example.com/second",
                "Tweet: https://x.com/Kotlin/status/1800000000000000000",
                "Docs: https://kotlinlang.org/docs/home.html",
            )

        val result = ContextUrlClassifier.classify(messages)

        assertEquals(listOf("https://x.com/Kotlin/status/1800000000000000000"), result.socialUrls)
        assertEquals(listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), result.youtubeUrls)
        assertEquals(
            listOf(
                "https://example.com/first",
                "https://example.com/second",
                "https://kotlinlang.org/docs/home.html",
            ),
            result.webUrls,
        )
        assertEquals(
            listOf(
                "https://example.com/first",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://example.com/second",
                "https://x.com/Kotlin/status/1800000000000000000",
                "https://kotlinlang.org/docs/home.html",
            ),
            result.allOriginalUrls,
        )
    }

    @Test
    fun `classifies Discord CDN media URLs and captured image attachments separately from webUrls`() {
        val messages =
            listOf(
                "Look at https://cdn.discordapp.com/attachments/10/20/diag1.png?ex=abc and https://media.discordapp.net/attachments/10/30/diag2.webp",
                "Also read https://example.com/blog and https://custom-cdn.example.org/captured.jpg",
            )
        val capturedAttachments =
            listOf(
                "https://custom-cdn.example.org/captured.jpg",
                "https://cdn.discordapp.com/attachments/10/20/diag1.png?ex=abc",
            )

        val result = ContextUrlClassifier.classify(messages, capturedAttachmentUrls = capturedAttachments)

        assertEquals(
            listOf(
                "https://cdn.discordapp.com/attachments/10/20/diag1.png?ex=abc",
                "https://media.discordapp.net/attachments/10/30/diag2.webp",
                "https://custom-cdn.example.org/captured.jpg",
            ),
            result.discordCdnUrls,
        )
        assertEquals(listOf("https://example.com/blog"), result.webUrls)
        assertEquals(
            listOf(
                "https://cdn.discordapp.com/attachments/10/20/diag1.png?ex=abc",
                "https://media.discordapp.net/attachments/10/30/diag2.webp",
                "https://example.com/blog",
                "https://custom-cdn.example.org/captured.jpg",
            ),
            result.allOriginalUrls,
        )
    }

    @Test
    fun `deduplicates equivalent URLs via basic normalization while preserving first occurrence order`() {
        val messages =
            listOf(
                "First: https://Example.COM:443/article/?id=1#top",
                "Dup slash: https://example.com/article?id=1",
                "Dup frag: https://example.com/article/?id=1#comments",
                "Distinct query: https://example.com/article?id=2",
                "Default http port: http://example.org:80/ and duplicate http://Example.ORG",
                "Custom port preserved: https://example.com:8443/api",
            )

        val result = ContextUrlClassifier.classify(messages)

        assertEquals(
            listOf(
                "https://Example.COM:443/article/?id=1#top",
                "https://example.com/article?id=2",
                "http://example.org:80/",
                "https://example.com:8443/api",
            ),
            result.webUrls,
        )
        assertEquals("https://example.com/article?id=1", ContextUrlClassifier.normalizeUrl("https://Example.COM:443/article/?id=1#top"))
        assertEquals("http://example.org", ContextUrlClassifier.normalizeUrl("http://example.org:80/"))
        assertEquals("https://example.com:8443/api", ContextUrlClassifier.normalizeUrl("https://example.com:8443/api/"))
    }

    @Test
    fun `caps webUrls at MAX_WEB_URLS while retaining all distinct URLs in allOriginalUrls`() {
        val webLinks = (1..25).map { idx -> "https://example.com/page-$idx" }
        val messages =
            listOf(
                "Social: https://x.com/Kotlin/status/1800000000000000000",
                "Duplicate first web: https://example.com/page-1/",
                webLinks.joinToString(" "),
                "YouTube: https://youtu.be/dQw4w9WgXcQ",
            )

        val result = ContextUrlClassifier.classify(messages)

        assertEquals(ContextUrlClassifier.MAX_WEB_URLS, result.webUrls.size)
        assertEquals(20, result.webUrls.size)
        assertEquals("https://example.com/page-1/", result.webUrls.first())
        assertEquals("https://example.com/page-20", result.webUrls.last())
        assertEquals(listOf("https://x.com/Kotlin/status/1800000000000000000"), result.socialUrls)
        assertEquals(listOf("https://youtu.be/dQw4w9WgXcQ"), result.youtubeUrls)
        assertEquals(27, result.allOriginalUrls.size)
        assertTrue("https://example.com/page-25" in result.allOriginalUrls)
    }

    @Test
    fun `ignores blank input and malformed or non-HTTP URLs without throwing`() {
        assertEquals(ClassifiedContextUrls(), ContextUrlClassifier.classify(""))
        assertEquals(ClassifiedContextUrls(), ContextUrlClassifier.classify("   \n\t  "))
        assertEquals(ClassifiedContextUrls(), ContextUrlClassifier.classify(emptyList()))
        assertEquals(ClassifiedContextUrls(), ContextUrlClassifier.classify(listOf("", "   ")))

        val malformedInputs =
            listOf(
                "not-a-url",
                "https://",
                "http://",
                "https://...",
                "https://.example.com/path",
                "https://example..com/path",
                "https://example.com:99999/bad-port",
                "https://example.com:abc/bad-port",
                "ftp://example.com/file.zip",
                "javascript:alert(1)",
                "mailto:user@example.com",
            )

        for (bad in malformedInputs) {
            assertNull(ContextUrlClassifier.classifyUrl(bad), "Expected null category for malformed/non-HTTP input: $bad")
            assertNull(ContextUrlClassifier.normalizeUrl(bad), "Expected null normalized URL for malformed/non-HTTP input: $bad")
        }

        val mixedMessage =
            malformedInputs.joinToString(" ") + " valid: https://example.com/good"
        val result = ContextUrlClassifier.classify(mixedMessage)
        assertEquals(listOf("https://example.com/good"), result.webUrls)
        assertEquals(listOf("https://example.com/good"), result.allOriginalUrls)
        assertEquals(ContextUrlCategory.WEB, ContextUrlClassifier.classifyUrl("https://example.com/good"))
    }
}
