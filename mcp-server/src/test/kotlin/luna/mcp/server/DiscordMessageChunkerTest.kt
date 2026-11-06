package luna.mcp.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscordMessageChunkerTest {
    @Test
    fun `message within 2000 characters is not chunked`() {
        val message = "Hello, Discord!"
        val chunks = DiscordMessageChunker.chunk(message)
        assertEquals(listOf("Hello, Discord!"), chunks)
    }

    @Test
    fun `message with exactly 2000 characters is not chunked`() {
        val message = "a".repeat(2000)
        val chunks = DiscordMessageChunker.chunk(message)
        assertEquals(1, chunks.size)
        assertEquals(2000, chunks[0].length)
    }

    @Test
    fun `message exceeding 2000 characters is split along newline boundary`() {
        val paragraph1 = "a".repeat(1500)
        val paragraph2 = "b".repeat(1000)
        val message = "$paragraph1\n$paragraph2"

        val chunks = DiscordMessageChunker.chunk(message)
        assertEquals(2, chunks.size)
        assertEquals(paragraph1, chunks[0])
        assertEquals(paragraph2, chunks[1])
        assertTrue(chunks.all { it.length <= 2000 })
    }

    @Test
    fun `message with no newlines exceeding 2000 characters is split into chunks of at most 2000 characters`() {
        val message = "c".repeat(4500)
        val chunks = DiscordMessageChunker.chunk(message)
        assertEquals(3, chunks.size)
        assertEquals(2000, chunks[0].length)
        assertEquals(2000, chunks[1].length)
        assertEquals(500, chunks[2].length)
        assertEquals(message, chunks.joinToString(""))
    }

    @Test
    fun `message with multiple paragraphs preserves text and does not exceed limit`() {
        val p1 = "First paragraph line 1\nFirst paragraph line 2"
        val p2 = "Second paragraph with more content " + "x".repeat(1950)
        val p3 = "Third paragraph short"
        val message = "$p1\n\n$p2\n\n$p3"

        val chunks = DiscordMessageChunker.chunk(message)
        assertTrue(chunks.all { it.length <= 2000 })
        assertTrue(chunks.size >= 2)
        assertEquals(message, chunks.joinToString("\n"))
    }

    @Test
    fun `boundary message of 2001 characters is split into two chunks`() {
        val message = "a".repeat(2001)
        val chunks = DiscordMessageChunker.chunk(message)
        assertEquals(2, chunks.size)
        assertEquals(2000, chunks[0].length)
        assertEquals(1, chunks[1].length)
        assertEquals(message, chunks.joinToString(""))
    }

    @Test
    fun `empty message returns single chunk with empty string`() {
        val chunks = DiscordMessageChunker.chunk("")
        assertEquals(listOf(""), chunks)
    }
}
