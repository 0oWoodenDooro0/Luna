package luna.summarizer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import luna.core.ai.AiTraceJson
import luna.core.ai.AiTraceRecord
import luna.core.ai.AiTraceRedactor
import luna.core.ai.JsonlAiTraceRecorder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal object SummaryAiTraceTestSupport {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC)

    private val clientJson =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    fun createTestClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(clientJson)
            }
        }

    fun createRecorder(
        tempDir: Path,
        apiKey: String = "secret-gemini-api-key",
    ): Pair<JsonlAiTraceRecorder, Path> {
        val traceDir = tempDir.resolve("ai-traces")
        val recorder =
            JsonlAiTraceRecorder(
                traceDir = traceDir,
                redactor = AiTraceRedactor(listOf(apiKey)),
                clock = fixedClock,
            )
        return recorder to traceDir.resolve("ai-2026-11-14.jsonl")
    }

    fun readTraces(traceFile: Path): List<AiTraceRecord> {
        if (!Files.exists(traceFile)) return emptyList()
        return Files
            .readAllLines(traceFile)
            .filter { it.isNotBlank() }
            .map { AiTraceJson.decodeFromString(AiTraceRecord.serializer(), it) }
    }
}
