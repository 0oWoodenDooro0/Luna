# AI Trace Architecture & Troubleshooting Guide

## Overview

Every Discord-triggered AI workflow across Luna's `Summary Pipeline` and `Chatter Pipeline` emits a single self-contained `AI Trace` JSONL record to daily files under `data/logs/ai/ai-YYYY-MM-DD.jsonl` (UTC date). Each line aggregates the Discord trigger context, ordered upstream `AI Attempt` exchanges (including `Participation Decision` checks, `fetch_earlier_messages` tool continuations, `Agentic Video Understanding` polling, and degraded fallbacks), top-level diagnostic indices, and final output.

```
Discord Trigger (Reaction / Mention / Ambient Message)
  └─> AiTraceSession (UUID traceId, coroutine-scoped via AiTraceContextElement)
        ├─> Attempt 1..N (DECISION / SUMMARY_GENERATE / SUMMARY_TOOL_CONTINUATION / CHATTER_GENERATE / AGENTIC_VIDEO)
        └─> finally { session.finish() }
              ├─> AiTraceRedactor -> AiTraceJson -> Channel(256) -> luna-ai-trace-writer -> data/logs/ai/ai-YYYY-MM-DD.jsonl
              └─> JsonLogger.log(layer="AI_TRACE", operation="traceRecorded", data={traceId, outcome, ...}) -> data/logs/luna-json.log
```

Design rationale and considered alternatives are documented in [ADR 0011](../adr/0011-ai-trace-jsonl-logging.md).

## Storage, Persistence & Isolation

- **Daily JSONL location**: `data/logs/ai/ai-YYYY-MM-DD.jsonl` (UTC date). Each line is a single compact JSON object serialized by [`AiTraceJson`](../../src/main/kotlin/luna/core/ai/AiTraceModels.kt#L121-L127) (`encodeDefaults = true`, `explicitNulls = true`, `prettyPrint = false`).
- **Docker volume persistence**: [`docker-compose.yml`](../../docker-compose.yml) mounts `./data:/app/data` for both `luna` and `mcp-server`, persisting `data/logs/ai/` on the host across container recreations. `/data/` is ignored by [`.gitignore`](../../.gitignore).
- **Separation from `luna-json.log` and `.data/luna.db`**:
  - [`logback.xml`](../../src/main/resources/logback.xml) writes general system, Kord, Ktor, and [`JsonLogger`](../../src/main/kotlin/luna/core/JsonLogger.kt) events to `data/logs/luna-json.log` with a shared `2MB` `totalSizeCap`. Keeping raw AI prompts/responses out of Logback prevents AI payloads from evicting operational logs.
  - `.data/luna.db` is the SQLite WAL database shared with the `Decoupled MCP Server`; keeping multi-kilobyte trace payloads out of SQLite avoids WAL bloat and enables direct `jq` / `rg` inspection.

## Configuration, Queueing & Redaction

### Environment Variables

Parsed by [`AiTraceConfig`](../../src/main/kotlin/luna/core/ai/AiTraceConfig.kt) (documented in [`.env.example`](../../.env.example); dual-namespace `ai.trace.*` keys are also supported via `PluginContext`):

| Environment Variable | Alias | Default | Behavior |
| :--- | :--- | :--- | :--- |
| `AI_TRACE_ENABLED` | `ai.trace.enabled` | `true` | Enables JSONL trace recording (including `SKIPPED` `Participation Decision` runs). When `false`, [`JsonlAiTraceRecorder.fromConfig`](../../src/main/kotlin/luna/core/ai/AiTraceRecorder.kt#L590-L605) returns [`NoOpAiTraceRecorder`](../../src/main/kotlin/luna/core/ai/AiTraceRecorder.kt#L64-L92). |
| `AI_TRACE_DIR` | `ai.trace.dir` | `data/logs/ai` | Directory where daily `ai-YYYY-MM-DD.jsonl` files are written. |
| `AI_TRACE_MAX_DAYS` | `ai.trace.maxDays` | `14` | Maximum retention age in days (positive integer; invalid/non-positive values fall back to `14`). |
| `AI_TRACE_MAX_TOTAL_MB` | `ai.trace.maxTotalMb` | `200` | Maximum total directory size in megabytes before oldest trace files are pruned (positive integer; invalid/non-positive values fall back to `200`). |

> [!NOTE]
> Standalone constructors and unit tests default to `NoOpAiTraceRecorder` unless explicitly injected with a temporary directory recorder, ensuring `./gradlew test` does not create `data/logs/ai` in the workspace.

### Bounded Async Queue, Retention & Fail-Open Guarantees

Implemented in [`JsonlAiTraceRecorder`](../../src/main/kotlin/luna/core/ai/AiTraceRecorder.kt#L343-L607):

- **Bounded non-blocking queue**: Enqueues serialized lines into a coroutine `Channel` with capacity `256` (`DEFAULT_QUEUE_CAPACITY`) via `trySend`. A single daemon thread (`luna-ai-trace-writer`) appends lines to the daily file.
- **Fail-open behavior**: If the queue is full or closed, or if JSON serialization or filesystem writes throw, `JsonlAiTraceRecorder` emits a warning via `JsonLogger.warn(layer="AI_TRACE", component="JsonlAiTraceRecorder", ...)` and drops the trace without blocking or failing Discord event handling.
- **Exception-safe session flush & graceful shutdown**: Both [`SummaryService`](../../src/main/kotlin/luna/summarizer/SummaryService.kt) and [`ChatterService`](../../src/main/kotlin/luna/chatter/ChatterService.kt) flush `AiTraceSession.finish()` inside a `finally` block (guarded by an `AtomicBoolean` for idempotency) so timeouts and exceptions are recorded. [`LunaHost.shutdown`](../../src/main/kotlin/luna/core/Main.kt#L134-L149) calls `aiTraceRecorder.flushAndClose(timeoutMs = 5000)` to drain queued traces before JVM exit.
- **Automatic retention pruning**: `pruneRetention` runs on writer startup, on UTC day rollover, and whenever written bytes reach `(maxTotalBytes / 4).coerceIn(1L, 50 MiB)`. It deletes `ai-YYYY-MM-DD.jsonl` files older than `AI_TRACE_MAX_DAYS` and then deletes the oldest files until total size is `<= AI_TRACE_MAX_TOTAL_MB`, always sparing the currently active target file.

### Secret & Binary Redaction Rules

[`AiTraceRedactor`](../../src/main/kotlin/luna/core/ai/AiTraceRedactor.kt) scrubs every trace before serialization:

- **URL query & header secrets**: Masks `?key=`, `&api_key=`, `&apiKey=`, `x-goog-api-key`, and `Authorization: Bearer ...` values to `******` across `endpoint`, `request.payload`, `responseRaw`, `errorMessage`, `finalOutput`, `errorSummary`, and `citations`.
- **Configured & session secrets**: Collects every non-blank sensitive value identified by `SecretMasker.isSensitiveKey(...)` from the unified config map plus per-session `session.registerSecret(apiKey)` calls (strings of length `>= 3`) and replaces occurrences with `******`.
- **Sensitive JSON object keys**: Any JSON object entry inside `request.payload` whose key matches `SecretMasker.isSensitiveKey(key)` or `x-goog-api-key` has its value replaced with `"******"`.
- **Binary & multimodal payloads**: Any `inlineData.data` or `inline_data.data` field in `request.payload` is replaced with `"[REDACTED_BINARY]"`. Multimodal images and videos delivered via `CDN Direct Ingestion` are stored only as `file_uri` / `uri` and `mime_type` metadata.

## JSONL Schema Reference

All schema models are defined in [`AiTraceModels.kt`](../../src/main/kotlin/luna/core/ai/AiTraceModels.kt).

### Top-Level `AI Trace` Fields (`AiTraceRecord`)

| Field | Type | Description |
| :--- | :--- | :--- |
| `traceId` | `String` | UUID uniquely identifying this `AI Trace` session. |
| `timestamp` | `String` | UTC ISO-8601 instant when the session started (e.g. `2026-11-14T08:12:34.567Z`). |
| `pipeline` | `AiTracePipeline` | Pipeline that handled the trigger: `SUMMARY` or `CHATTER`. |
| `trigger` | `AiTraceTrigger` | Discord trigger source: `REACTION`, `MENTION`, or `AMBIENT`. |
| `discord` | `AiTraceDiscordContext` | Discord origin metadata (`guildId`, `channelId`, `messageId`, `authorId`, `reactingUserId`, `isDirectMention`). |
| `outcome` | `AiTraceOutcome` | Terminal workflow status: `SUCCESS`, `DEGRADED_SUCCESS`, `SKIPPED`, or `FAILED`. |
| `degraded` | `Boolean` | `true` when any fallback path ran (e.g. stripped `googleSearch`, stripped multimodal media/images, `fetch_earlier_messages` fallback, or `Agentic Video Understanding` fallback). |
| `failureCategory` | `AiTraceFailureCategory?` | Normalized failure classification (`null` on clean `SUCCESS` or normal `SKIPPED`). |
| `fallbackPath` | `List<String>` | Ordered stage labels executed in this trace, formatted as `STAGE` or `STAGE:variant` (e.g. `["SUMMARY_GENERATE:grounded+lookback", "SUMMARY_TOOL_CONTINUATION:fetch_earlier_messages"]`, `["CHATTER_GENERATE", "CHATTER_GENERATE:no-search", "CHATTER_GENERATE:no-media"]`, `["DECISION:multimodal", "DECISION:text-only"]`). |
| `totalLatencyMs` | `Long` | Wall-clock duration in milliseconds from session start to `finish()`. |
| `attemptCount` | `Int` | Number of entries in `attempts` (`attempts.size`). |
| `finalOutput` | `String?` | Final user-facing output delivered to Discord (truncated/sanitized text), or `null` when skipped/failed. |
| `citations` | `List<GroundingCitation>` | Search Grounding citations (`[{"title": "...", "url": "..."}]`); empty list `[]` if none. |
| `errorSummary` | `String?` | Human-readable failure, degradation, or skip explanation (including `noul`, `confidence`, and `threshold` for `SKIPPED` decisions). |
| `attempts` | `List<AiAttemptRecord>` | Chronologically ordered `AI Attempt` records executed during this trace. |

#### `discord` Subfields (`AiTraceDiscordContext`)

| Field | Type | Description |
| :--- | :--- | :--- |
| `guildId` | `String?` | Discord guild snowflake ID (`null` for DMs or standalone calls). |
| `channelId` | `String` | Discord channel snowflake ID (or `"direct"` / `"standalone"` for direct client invocations). |
| `messageId` | `String` | Discord target message snowflake ID (or `"direct"` / `"standalone"`). |
| `authorId` | `String?` | Target message author snowflake ID, if available. |
| `reactingUserId` | `String?` | User snowflake ID that added the `Summary Reaction` (`null` for `CHATTER`). |
| `isDirectMention` | `Boolean` | `true` when Luna was directly `@`-mentioned (`MENTION` trigger); `false` otherwise. |

#### Top-Level Enum Values

- **`AiTracePipeline`**: `SUMMARY`, `CHATTER`
- **`AiTraceTrigger`**: `REACTION`, `MENTION`, `AMBIENT`
- **`AiTraceOutcome`**:
  - `SUCCESS`: Completed on the primary path without degradation.
  - `DEGRADED_SUCCESS`: Produced output after at least one fallback (`degraded: true`), such as stripping `googleSearch`, dropping images/video, or falling back from `AGENTIC_VIDEO`.
  - `SKIPPED`: Intentionally skipped without error (for example, `Chatter Pipeline` `Participation Decision` returned `shouldParticipate = false`, or `SummaryService` filtered the message as noise/bot).
  - `FAILED`: Terminated without producing output (API error, safety block, timeout, parse error, missing config, etc.).
- **`AiTraceFailureCategory`**:
  - `RATE_LIMIT` — HTTP 429 rate limiting from Gemini.
  - `TIMEOUT` — Request, socket, coroutine, or agentic polling timeout.
  - `HTTP_ERROR` — Non-2xx HTTP status (other than 429) or embedded API `error` payload.
  - `SAFETY_BLOCK` — `promptFeedback.blockReason` present or safety `finishReason` (`SAFETY`, `RECITATION`, `PROHIBITED_CONTENT`, `BLOCKLIST`, `SPII`).
  - `PARSE_ERROR` — Invalid/empty JSON response, malformed tool call, or missing `noul` score.
  - `EMPTY_OUTPUT` — Blank/empty candidates or output sanitized to blank.
  - `NOT_CONFIGURED` — Missing `GEMINI_API_KEY`.
  - `DURATION_EXCEEDED` — Video exceeded the 2-hour (`7200s`) cap in `Summary Pipeline`.
  - `CONTEXT_ERROR` — Failed to fetch Discord message/channel/slice or blank input context.
  - `UNEXPECTED_ERROR` — Unhandled runtime/network/delivery exception.

### `AI Attempt` Fields (`AiAttemptRecord`)

| Field | Type | Description |
| :--- | :--- | :--- |
| `attemptIndex` | `Int` | 1-based ordinal index of this attempt within `attempts[]`. |
| `stage` | `AiAttemptStage` | Execution stage: `DECISION`, `SUMMARY_GENERATE`, `SUMMARY_TOOL_CONTINUATION`, `CHATTER_GENERATE`, or `AGENTIC_VIDEO`. |
| `model` | `String?` | Gemini model ID used for this call (e.g. `gemini-3.5-flash-lite`, `gemini-decision-1`). |
| `endpoint` | `String?` | Target upstream URL with API keys masked (e.g. `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent`). |
| `latencyMs` | `Long` | Elapsed duration of this attempt in milliseconds (for `AGENTIC_VIDEO`, spans submission through final polling completion). |
| `httpStatus` | `Int?` | Upstream HTTP status code (`null` on client-side timeout/network exception before response). |
| `request` | `AiAttemptRequest?` | Structured request payload and metadata (see subfields below). |
| `responseRaw` | `String?` | Raw upstream response body string (for `AGENTIC_VIDEO`, the latest submission or polling response body). |
| `finishReason` | `String?` | Candidate `finishReason` (e.g. `STOP`, `SAFETY`, `RECITATION`, `MAX_TOKENS`). |
| `blockReason` | `String?` | `promptFeedback.blockReason` when Gemini blocked the prompt. |
| `usageMetadata` | `GeminiUsageMetadata?` | Token accounting object (`promptTokenCount`, `candidatesTokenCount`, `totalTokenCount`), if returned by Gemini. |
| `pollingAttempts` | `Int?` | Number of `GET /v1beta/interactions/{id}` polling iterations executed (`AGENTIC_VIDEO` only; `null` for other stages). |
| `parsedOutcome` | `AiAttemptOutcome` | Attempt-level outcome: `SUCCESS`, `SKIPPED`, `TOOL_CALL`, or `ERROR`. |
| `errorMessage` | `String?` | Sanitized attempt error reason when `parsedOutcome == ERROR` (`null` otherwise). |

#### `request` Subfields (`AiAttemptRequest`)

| Field | Type | Description |
| :--- | :--- | :--- |
| `payload` | `JsonElement?` | Full serialized request JSON (`GeminiGenerateContentRequest`, `GeminiDecisionRequest`, or `GeminiInteractionRequest`) after secret and binary redaction. |
| `hasImages` | `Boolean` | `true` if `Chat Image Attachment` entries were included in this attempt. |
| `imageCount` | `Int` | Number of image attachments included in this attempt. |
| `hasVideo` | `Boolean` | `true` if a `videoUri` was included in this attempt. |
| `videoDurationSeconds` | `Long?` | Video duration in seconds when known, otherwise `null`. |
| `transcriptLength` | `Int` | Character length of the prompt/transcript input sent in this attempt. |

#### `AI Attempt` Enum Values

- **`AiAttemptStage`**:
  - `DECISION`: `POST /v1beta/decisions` (`GeminiDecisionClient`) evaluating binary `noul` participation probability.
  - `SUMMARY_GENERATE`: `POST /v1beta/models/{model}:generateContent` (`GeminiSummarizer`) initial or fallback summary generation.
  - `SUMMARY_TOOL_CONTINUATION`: Follow-up `:generateContent` after fulfilling a `fetch_earlier_messages` tool call.
  - `CHATTER_GENERATE`: `POST /v1beta/models/{model}:generateContent` (`ChatterGenerator`) conversational reply generation.
  - `AGENTIC_VIDEO`: `POST /v1beta/interactions` + `GET /v1beta/interactions/{id}` polling (`GeminiInteractionClient`) for long videos (`> 5m`).
- **`AiAttemptOutcome`**: `SUCCESS`, `SKIPPED`, `TOOL_CALL`, `ERROR`.

## `JsonLogger` Correlation (`data/logs/luna-json.log`)

Whenever a trace session finishes, [`AiTraceSession.finish`](../../src/main/kotlin/luna/core/ai/AiTraceRecorder.kt#L299-L318) emits one lightweight correlation event via [`JsonLogger.log`](../../src/main/kotlin/luna/core/JsonLogger.kt) into `data/logs/luna-json.log`:

- `layer`: `"AI_TRACE"`
- `component`: `"SUMMARY"` or `"CHATTER"` (`pipeline.name`)
- `operation`: `"traceRecorded"`
- `status`: `"SUCCESS"`, `"DEGRADED_SUCCESS"`, `"SKIPPED"`, or `"FAILED"` (`outcome.name`)
- `data`:
  ```json
  {
    "traceId": "<uuid>",
    "pipeline": "SUMMARY",
    "trigger": "REACTION",
    "outcome": "SUCCESS",
    "degraded": false,
    "attemptCount": 1,
    "totalLatencyMs": 842
  }
  ```

`JsonlAiTraceRecorder` operational warnings (full queue, serialization error, write failure, retention cleanup error, or shutdown flush timeout) also log under `layer: "AI_TRACE"`, `component: "JsonlAiTraceRecorder"`.

## Diagnostic Recipes (`jq` & `rg`)

Run these commands from the repository root (or the host `./data` mount directory). Replace `data/logs/ai/ai-*.jsonl` with a specific date file (e.g. `data/logs/ai/ai-2026-11-14.jsonl`) to narrow the search window.

### 1. Find `FAILED` or `DEGRADED_SUCCESS` Traces

```bash
jq -c 'select(.outcome == "FAILED" or .outcome == "DEGRADED_SUCCESS") | {traceId, timestamp, pipeline, trigger, outcome, degraded, failureCategory, fallbackPath, errorSummary}' data/logs/ai/ai-*.jsonl
```

Fast pre-filter with `rg`:

```bash
rg '"outcome":"(FAILED|DEGRADED_SUCCESS)"' data/logs/ai/
```

### 2. Inspect Safety Blocks (`blockReason` / `SAFETY_BLOCK`) and Timeouts (`TIMEOUT`)

Safety blocks (prompt feedback `blockReason`, safety `finishReason`, or `failureCategory == "SAFETY_BLOCK"`):

```bash
jq 'select(.failureCategory == "SAFETY_BLOCK" or any(.attempts[]?; .blockReason != null or (.finishReason | IN("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII")))) | {traceId, pipeline, outcome, failureCategory, errorSummary, attempts: [.attempts[] | {attemptIndex, stage, httpStatus, finishReason, blockReason, errorMessage, responseRaw}]}' data/logs/ai/ai-*.jsonl
```

Timeouts (`failureCategory == "TIMEOUT"` or attempt timeout errors):

```bash
jq 'select(.failureCategory == "TIMEOUT" or any(.attempts[]?; (.errorMessage // "") | test("timeout|timed out"; "i"))) | {traceId, pipeline, trigger, totalLatencyMs, failureCategory, fallbackPath, errorSummary, attempts: [.attempts[] | {attemptIndex, stage, latencyMs, parsedOutcome, errorMessage}]}' data/logs/ai/ai-*.jsonl
```

Fast `rg` scan for either safety blocks or timeouts:

```bash
rg '"failureCategory":"(SAFETY_BLOCK|TIMEOUT)"|"blockReason":"[^"]+"' data/logs/ai/
```

### 3. Diagnose `SKIPPED` `Participation Decision` Outcomes (`noul` / `DECISION` Attempt)

Extracts the `errorSummary` (which includes `noul`, `confidence`, and `threshold`) and parses `responseRaw` inside each `DECISION` attempt:

```bash
jq -c 'select(.pipeline == "CHATTER" and .outcome == "SKIPPED") | {traceId, timestamp, channelId: .discord.channelId, messageId: .discord.messageId, errorSummary, decisionAttempts: [.attempts[] | select(.stage == "DECISION") | {attemptIndex, latencyMs, parsedOutcome, decision: ((.responseRaw // "{}" | try fromjson catch null) | .answers.should_participate // null)}]}' data/logs/ai/ai-*.jsonl
```

Fast `rg` filter:

```bash
rg '"pipeline":"CHATTER".*"outcome":"SKIPPED"' data/logs/ai/
```

### 4. Check Token Usage (`usageMetadata`) Across Attempts

Per-trace token breakdown:

```bash
jq -c '{traceId, pipeline, outcome, totalTokens: ([.attempts[]?.usageMetadata?.totalTokenCount // 0] | add), attempts: [.attempts[] | select(.usageMetadata != null) | {attemptIndex, stage, model, promptTokenCount: .usageMetadata.promptTokenCount, candidatesTokenCount: .usageMetadata.candidatesTokenCount, totalTokenCount: .usageMetadata.totalTokenCount}]}' data/logs/ai/ai-*.jsonl
```

Aggregate token totals across one or more JSONL files:

```bash
jq -s '{traces: length, promptTokens: ([.[].attempts[]?.usageMetadata?.promptTokenCount // 0] | add), candidatesTokens: ([.[].attempts[]?.usageMetadata?.candidatesTokenCount // 0] | add), totalTokens: ([.[].attempts[]?.usageMetadata?.totalTokenCount // 0] | add)}' data/logs/ai/ai-*.jsonl
```

Fast `rg` check for traces reporting token counts:

```bash
rg '"totalTokenCount":[0-9]+' data/logs/ai/
```

### 5. Correlate Discord `messageId` / `channelId` and Cross-Reference `traceId` in `data/logs/luna-json.log`

Look up traces for a specific Discord `messageId` or `channelId`:

```bash
jq -c --arg msgId "123456789012345678" 'select(.discord.messageId == $msgId) | {traceId, timestamp, pipeline, trigger, outcome, degraded, totalLatencyMs, attemptCount}' data/logs/ai/ai-*.jsonl
```

```bash
jq -c --arg chId "987654321098765432" 'select(.discord.channelId == $chId) | {traceId, timestamp, pipeline, trigger, messageId: .discord.messageId, outcome, degraded}' data/logs/ai/ai-*.jsonl
```

Cross-reference a `traceId` across both `luna-json.log` and `data/logs/ai/`:

```bash
rg "YOUR_TRACE_ID" data/logs/luna-json.log data/logs/ai/
```

Inspect `AI_TRACE` correlation events in `data/logs/luna-json.log`:

```bash
jq -c 'select(.layer == "AI_TRACE") | {timestamp: ."@timestamp", component, operation, status, data}' data/logs/luna-json.log
```

### 6. Inspect `AGENTIC_VIDEO` Runs and `pollingAttempts`

```bash
jq -c 'select(any(.attempts[]?; .stage == "AGENTIC_VIDEO")) | {traceId, pipeline, trigger, outcome, degraded, totalLatencyMs, fallbackPath, agenticAttempts: [.attempts[] | select(.stage == "AGENTIC_VIDEO") | {attemptIndex, model, latencyMs, httpStatus, pollingAttempts, videoDurationSeconds: .request.videoDurationSeconds, parsedOutcome, errorMessage}]}' data/logs/ai/ai-*.jsonl
```

Fast `rg` filter for `AGENTIC_VIDEO` runs:

```bash
rg '"stage":"AGENTIC_VIDEO"' data/logs/ai/
```
