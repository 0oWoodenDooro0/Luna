package luna.preview

import dev.socialpeek.SocialPeek
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialPreviewServiceTest {
    @Test
    fun `canResolveSocial strictly excludes non-social and generic web URLs`() {
        val nonSocialUrls =
            listOf(
                "https://example.com",
                "https://example.com/article/123",
                "https://github.com/0oWoodenDooro0/Luna",
                "https://news.ycombinator.com/item?id=12345",
                "https://en.wikipedia.org/wiki/Kotlin_(programming_language)",
                "https://blog.google/technology/ai/",
                "http://localhost:8080/test",
            )

        for (url in nonSocialUrls) {
            // General resolution (for summarizer / text extraction) may match GenericWebResolver
            assertTrue(
                SocialPeek.canResolve(url),
                "Expected generic SocialPeek.canResolve to resolve non-social URL: $url",
            )

            // Social preview auto-previewing MUST NOT resolve generic non-social URLs
            assertFalse(
                SocialPeek.canResolveSocial(url),
                "SocialPeek.canResolveSocial must reject non-social URL to prevent auto-preview scope creep: $url",
            )
        }
    }

    @Test
    fun `canResolveSocial recognizes dedicated social platform URLs`() {
        val socialUrls =
            listOf(
                "https://x.com/Kotlin/status/1800000000000000000",
                "https://twitter.com/Kotlin/status/1800000000000000000",
                "https://www.reddit.com/r/Kotlin/comments/12345/discussion/",
                "https://www.instagram.com/p/C-example123/",
                "https://www.threads.net/@zuck/post/C-example123",
                "https://www.bilibili.com/video/BV1xx411c7mD",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ",
            )

        for (url in socialUrls) {
            assertTrue(
                SocialPeek.canResolve(url),
                "Expected SocialPeek.canResolve to match social URL: $url",
            )
            assertTrue(
                SocialPeek.canResolveSocial(url),
                "Expected SocialPeek.canResolveSocial to accept social URL: $url",
            )
        }
    }
}
