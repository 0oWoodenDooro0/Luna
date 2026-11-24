package luna.chatter.memory

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import luna.core.ai.GeminiEmbeddingClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryDistillerImportanceKeywordsTest {
    @Test
    fun `stage 1 prompt includes importance rubric and keyword instructions and parser clamps and sanitizes`() {
        val instruction = MemoryDistillationPrompt.EXTRACTION_SYSTEM_INSTRUCTION
        assertTrue(instruction.contains("importance"), "Expected importance rubric in extraction prompt")
        assertTrue(instruction.contains("1") && instruction.contains("5"), "Expected 1-5 scale in extraction prompt")
        assertTrue(instruction.contains("keywords"), "Expected keywords instruction in extraction prompt")

        val nowMs = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        val oversizedKeywords = "alpha beta gamma delta epsilon " + "x".repeat(150)
        val rawJson =
            """
            [
              {"scope": "user", "target_user_id": "111", "content": "Alice is allergic to peanuts", "importance": 5, "keywords": "peanuts   allergy   food"},
              {"scope": "user", "target_user_id": "111", "content": "Alice drank tea today", "importance": 0, "keywords": "$oversizedKeywords"},
              {"scope": "user", "target_user_id": "111", "content": "Alice lives in Taipei", "importance": 99, "keywords": ["taipei", "residence", "home"]},
              {"scope": "guild", "target_user_id": null, "content": "Server uses Kotlin", "importance": "high", "keywords": null},
              {"scope": "guild", "target_user_id": null, "content": "Server plays Valorant"}
            ]
            """.trimIndent()

        val parsed = MemoryDistillationPrompt.parseExtractionResponse(rawJson, nowMs, ZoneOffset.UTC).getOrThrow()
        assertEquals(5, parsed.size)

        assertEquals(5, parsed[0].importance)
        assertEquals("peanuts allergy food", parsed[0].keywords)

        assertEquals(ChatterMemoryStore.MIN_IMPORTANCE, parsed[1].importance)
        assertTrue(
            parsed[1].keywords.length <= ChatterMemoryStore.MAX_KEYWORDS_CHARS,
            "Expected sanitized keywords to be capped at ${ChatterMemoryStore.MAX_KEYWORDS_CHARS}",
        )

        assertEquals(ChatterMemoryStore.MAX_IMPORTANCE, parsed[2].importance)
        assertEquals("taipei residence home", parsed[2].keywords)

        assertEquals(ChatterMemoryStore.DEFAULT_IMPORTANCE, parsed[3].importance)
        assertEquals("", parsed[3].keywords)

        assertEquals(ChatterMemoryStore.DEFAULT_IMPORTANCE, parsed[4].importance)
        assertEquals("", parsed[4].keywords)
    }

    @Test
    fun `deduplicateBatchCandidates preserves highest importance and non-blank keywords`() {
        val candidates =
            listOf(
                FactCandidate(
                    scope = MemoryScope.USER,
                    targetUserId = "111",
                    content = "Alice likes coffee",
                    importance = 2,
                    keywords = "coffee",
                ),
                FactCandidate(
                    scope = MemoryScope.USER,
                    targetUserId = "111",
                    content = "Alice likes coffee every morning",
                    importance = 4,
                    keywords = "",
                ),
            )

        val deduped = MemoryCandidateProcessor.deduplicateBatchCandidates(candidates)
        assertEquals(1, deduped.size)
        assertEquals(4, deduped.single().importance)
        assertEquals("coffee", deduped.single().keywords)
    }

    @Test
    fun `stage 2 reconciliation prompt and buildMutations preserve and update importance and keywords and embed with keywords`() =
        runTest {
            val candidates =
                listOf(
                    FactCandidate(
                        scope = MemoryScope.USER,
                        targetUserId = "111",
                        content = "Alice is allergic to peanuts",
                        importance = 5,
                        keywords = "peanuts allergy",
                    ),
                    FactCandidate(
                        scope = MemoryScope.GUILD,
                        targetUserId = null,
                        content = "Server hosts Friday movie night",
                        importance = 2,
                        keywords = "movie friday",
                    ),
                )
            val existingHit =
                ChatterMemorySearchHit(
                    memory =
                        ChatterMemoryRecord(
                            id = 42L,
                            guildId = "2000",
                            scope = MemoryScope.GUILD,
                            targetUserId = null,
                            content = "Server hosts Thursday movie night",
                            createdAt = 1000L,
                            updatedAt = 1000L,
                            lastAccessedAt = 1000L,
                            importance = 2,
                            keywords = "movie thursday",
                        ),
                    score = 0.9,
                )
            val prompt =
                MemoryDistillationPrompt.buildReconciliationPrompt(
                    candidates = candidates,
                    hitsPerCandidate = listOf(emptyList(), listOf(existingHit)),
                )
            assertTrue(prompt.contains("importance=5"), "Expected candidate importance in prompt")
            assertTrue(prompt.contains("keywords=peanuts allergy"), "Expected candidate keywords in prompt")
            assertTrue(prompt.contains("importance=2"), "Expected existing hit importance in prompt")
            assertTrue(prompt.contains("keywords=movie thursday"), "Expected existing hit keywords in prompt")

            val embeddedTexts = mutableListOf<String>()
            val embeddingHttp =
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            val reqBody = (request.body as io.ktor.http.content.TextContent).text
                            embeddedTexts.add(reqBody)
                            val reqJson = Json.parseToJsonElement(reqBody).jsonObject
                            val count = (reqJson["requests"] as? JsonArray)?.size ?: 0
                            val embeddingsJson =
                                (0 until count).joinToString(",") { idx ->
                                    """{"values": ${List(768) { if (it == 0) (idx + 1).toFloat() else 0.0f }}}"""
                                }
                            respond(
                                content = """{"embeddings": [$embeddingsJson]}""",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }
                    }
                }
            val embeddingClient = GeminiEmbeddingClient(httpClient = embeddingHttp, apiKey = "test-key")
            val candidateEmbeddings = listOf(FloatArray(768) { 0.1f }, FloatArray(768) { 0.2f })
            val decisions =
                MemoryDistillationPrompt
                    .parseReconciliationResponse(
                        """
                        [
                          {"action": "INSERT", "candidate_index": 0},
                          {"action": "UPDATE", "memory_id": 42, "candidate_index": 1, "content": "Server hosts Friday movie nights", "importance": 4, "keywords": "movie friday cinema"}
                        ]
                        """.trimIndent(),
                    ).getOrThrow()

            val mutations =
                MemoryDistillationPrompt.buildMutations(
                    guildId = "2000",
                    candidates = candidates,
                    candidateEmbeddings = candidateEmbeddings,
                    decisions = decisions,
                    embeddingClient = embeddingClient,
                    clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                )

            assertEquals(2, mutations.size)
            val insert = mutations[0] as MemoryMutation.Insert
            assertEquals(5, insert.importance)
            assertEquals("peanuts allergy", insert.keywords)

            val update = mutations[1] as MemoryMutation.Update
            assertEquals(42L, update.id)
            assertEquals(4, update.importance)
            assertEquals("movie friday cinema", update.keywords)
            assertEquals(1, embeddedTexts.size)
            assertTrue(embeddedTexts.single().contains("movie friday cinema"), "Expected re-embedding to include updated keywords")
        }
}
