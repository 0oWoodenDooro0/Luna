package dev.socialpeek.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeekPostTest {
    @Test
    fun `PeekPost should support community comments with defaults`() {
        val post =
            PeekPost(
                platform = Platform.GENERIC,
                id = "test-1",
                originalUrl = "https://example.com/article",
                author = Author(username = "editor"),
                content = "Article content",
            )

        assertTrue(post.comments.isEmpty())
    }

    @Test
    fun `PeekPost should support Comment serialization and properties`() {
        val comment =
            Comment(
                id = "c1",
                author = "alice",
                content = "Great article!",
                score = 42,
                createdAtEpochSeconds = 1715000000L,
            )

        val post =
            PeekPost(
                platform = Platform.REDDIT,
                id = "post-1",
                originalUrl = "https://reddit.com/r/test/comments/123",
                author = Author(username = "bob"),
                content = "Post content",
                comments = listOf(comment),
            )

        assertEquals(1, post.comments.size)
        assertEquals("c1", post.comments[0].id)
        assertEquals("alice", post.comments[0].author)
        assertEquals("Great article!", post.comments[0].content)
        assertEquals(42L, post.comments[0].score)
        assertEquals(1715000000L, post.comments[0].createdAtEpochSeconds)

        val json = Json { prettyPrint = false }
        val encoded = json.encodeToString(PeekPost.serializer(), post)
        val decoded = json.decodeFromString(PeekPost.serializer(), encoded)

        assertEquals(post, decoded)
        assertEquals(1, decoded.comments.size)
        assertEquals("alice", decoded.comments[0].author)
    }
}
