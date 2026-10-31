package dev.socialpeek.resolver.generic

import dev.socialpeek.SocialPeekClient
import dev.socialpeek.model.Media
import dev.socialpeek.model.Platform
import dev.socialpeek.resolver.reddit.RedditResolver
import dev.socialpeek.test.createMockHttpClient
import dev.socialpeek.test.htmlResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GenericWebResolverTest {
    private val resolver = GenericWebResolver()

    @Test
    fun `canResolve should match http and https urls and reject non-http schemes`() {
        assertTrue(resolver.canResolve("http://example.com/article"))
        assertTrue(resolver.canResolve("https://blog.jetbrains.com/kotlin/2024/05/kotlin-2-0-0-released/"))
        assertTrue(resolver.canResolve("https://en.wikipedia.org/wiki/Kotlin_(programming_language)"))

        assertFalse(resolver.canResolve("ftp://example.com/resource"))
        assertFalse(resolver.canResolve("file:///path/to/file"))
        assertFalse(resolver.canResolve("invalid-url"))
        assertFalse(resolver.canResolve(""))
    }

    @Test
    fun `resolve should extract clean body text from article tag`() =
        runTest {
            val html =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Sample Article - Tech Blog</title>
                    <meta property="og:description" content="A summary of the article." />
                </head>
                <body>
                    <header><nav>Navigation menu</nav></header>
                    <article>
                        <script>console.log("ad script");</script>
                        <h1>Article Heading</h1>
                        <p>First paragraph of the main article content.</p>
                        <p>Second paragraph with more interesting details.</p>
                    </article>
                    <footer>Footer content</footer>
                </body>
                </html>
                """.trimIndent()

            val client = createMockHttpClient { htmlResponse(html) }
            val post = resolver.resolve("https://techblog.com/posts/sample-article", client)

            assertEquals(Platform.GENERIC, post.platform)
            assertEquals("Sample Article - Tech Blog", post.title)
            assertEquals("First paragraph of the main article content.\n\nSecond paragraph with more interesting details.", post.content)
            assertEquals("A summary of the article.", post.rawData["description"])
        }

    @Test
    fun `resolve should extract clean body text from main tag when article tag is absent`() =
        runTest {
            val html =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Documentation Page</title>
                </head>
                <body>
                    <main>
                        <p>Welcome to the main documentation guide.</p>
                        <p>Follow the steps below to get started.</p>
                    </main>
                </body>
                </html>
                """.trimIndent()

            val client = createMockHttpClient { htmlResponse(html) }
            val post = resolver.resolve("https://docs.example.com/guide", client)

            assertEquals(Platform.GENERIC, post.platform)
            assertEquals("Documentation Page", post.title)
            assertEquals("Welcome to the main documentation guide.\n\nFollow the steps below to get started.", post.content)
        }

    @Test
    fun `resolve should extract clean body text from concatenated p tags when article and main are absent`() =
        runTest {
            val html =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Simple Post</title>
                </head>
                <body>
                    <div class="content-wrapper">
                        <p>First paragraph in wrapper.</p>
                        <p>Second paragraph in wrapper.</p>
                        <p>Third paragraph in wrapper.</p>
                    </div>
                </body>
                </html>
                """.trimIndent()

            val client = createMockHttpClient { htmlResponse(html) }
            val post = resolver.resolve("https://example.com/simple", client)

            assertEquals(Platform.GENERIC, post.platform)
            assertEquals("Simple Post", post.title)
            assertEquals("First paragraph in wrapper.\n\nSecond paragraph in wrapper.\n\nThird paragraph in wrapper.", post.content)
        }

    @Test
    fun `resolve should fallback to og title when title tag is missing or blank`() =
        runTest {
            val html =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>   </title>
                    <meta property="og:title" content="Fallback OpenGraph Title" />
                </head>
                <body>
                    <article><p>Some text.</p></article>
                </body>
                </html>
                """.trimIndent()

            val client = createMockHttpClient { htmlResponse(html) }
            val post = resolver.resolve("https://example.com/og-test", client)

            assertEquals("Fallback OpenGraph Title", post.title)
        }

    @Test
    fun `resolve should clean excess whitespace and non-breaking spaces`() =
        runTest {
            val html =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Whitespace Test</title>
                </head>
                <body>
                    <article>
                        <p>&nbsp;&nbsp;First&nbsp;&nbsp;&nbsp;paragraph&nbsp;&nbsp;with&nbsp;spaces.&nbsp;&nbsp;</p>
                        <p>   Second    paragraph    with   tabs	and   spaces.   </p>
                    </article>
                </body>
                </html>
                """.trimIndent()

            val client = createMockHttpClient { htmlResponse(html) }
            val post = resolver.resolve("https://example.com/whitespace", client)

            assertEquals("First paragraph with spaces.\n\nSecond paragraph with tabs and spaces.", post.content)
        }

    @Test
    fun `resolve should fallback to og description when body text is absent`() =
        runTest {
            val html =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Empty Body Page</title>
                    <meta property="og:description" content="Summary from og:description." />
                </head>
                <body>
                </body>
                </html>
                """.trimIndent()

            val client = createMockHttpClient { htmlResponse(html) }
            val post = resolver.resolve("https://example.com/empty-body", client)

            assertEquals("Summary from og:description.", post.content)
            assertEquals("Summary from og:description.", post.rawData["description"])
        }

    @Test
    fun `resolve should extract og image into media if present`() =
        runTest {
            val html =
                """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Image Article</title>
                    <meta property="og:image" content="https://example.com/preview.png" />
                </head>
                <body>
                    <article><p>Article content.</p></article>
                </body>
                </html>
                """.trimIndent()

            val client = createMockHttpClient { htmlResponse(html) }
            val post = resolver.resolve("https://example.com/image-article", client)

            assertEquals(1, post.media.size)
            val img = assertIs<Media.Image>(post.media.first())
            assertEquals("https://example.com/preview.png", img.url)
        }

    @Test
    fun `SocialPeekClient should use GenericWebResolver as fallback for arbitrary websites`() {
        val client = SocialPeekClient()

        assertTrue(client.canResolve("https://wikipedia.org/wiki/Kotlin"))
        val genericResolver = client.findResolver("https://wikipedia.org/wiki/Kotlin")
        assertIs<GenericWebResolver>(genericResolver)

        // Specific social resolvers should take precedence
        val redditResolver = client.findResolver("https://reddit.com/r/kotlindev/comments/123/test")
        assertIs<RedditResolver>(redditResolver)
    }
}
