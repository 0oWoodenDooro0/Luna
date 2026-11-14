# AI Trace Architecture & Troubleshooting Guide

## Overview

Every Discord-triggered AI workflow across Luna's `Summary Pipeline` and `Chatter Pipeline` emits a single self-contained `AI Trace` JSONL record to daily files under `data/logs/ai/ai-YYYY-MM-DD.jsonl` (UTC date). Each line aggregates the Discord trigger context, ordered upstream `AI Attempt` exchanges (including `Participation Decision` checks with optional multimodal-to-text retry, `fetch_earlier_messages` tool continuations via `previous_interaction_id`, and `Agentic Video Understanding` polling), top-level diagnostic indices, and final output. Both `GeminiSummarizer` and `ChatterGenerator` invoke `/v1beta/interactions` without tool/media degraded fallback retries.

```
Discord Trigger (Reaction / Mention / Ambient Message)
  └─> AiTraceSession (UUID traceId, coroutine-scoped via AiTraceContextElement)
        ├─> Attempt 1..N (DECISION / SUMMARY_GENERATE / SUMMARY_TOOL_CONTINUATION / CHATTER_GENERATE / AGENTIC_VIDEO)
        └─> finally { session.finish() }
              ├─> AiTraceRedactor -> AiTraceJson -> Channel(256) -> luna-ai-trace-writer -> data/logs/ai/ai-YYYY-MM-DD.jsonl
              └─> JsonLogger.log(layer="AI_TRACE", operation="traceRecorded", data={traceId, outcome, ...}) -> data/logs/luna-json.log
```

Design rationale and considered alternatives are documented in [ADR 0011](../adr/0011-ai-trace-jsonl-logging.md) and [ADR 0012](../adr/0012-interactions-api-url-context-and-no-fallback.md).

## Storage, Persistence & Isolation

- **Daily JSONL location**: `data/logs/ai/ai-YYYY-MM-DD.jsonl` (UTC date). Each line is a single compact JSON object serialized by [`AiTraceJson`](../../src/main/kotlin/luna/core/ai/AiTraceModels.kt#L127-L133) (`encodeDefaults = true`, `explicitNulls = true`, `prettyPrint = false`).
- **Docker volume persistence**: [`docker-compose.yml`](../../docker-compose.yml) mounts `./data:/app/data` for both `luna` and `mcp-server`, persisting `data/logs/ai/` on the host across container recreations. `/data/` is ignored by [`.gitignore`](../../.gitignore).
- **Separation from `luna-json.log` and `.data/luna.db`**:
  - [`logback.xml`](../../src/main/resources/logback.xml) writes general system, Kord, Ktor, and [`JsonLogger`](../../src/main/kotlin/luna/core/JsonLogger.kt) events to `data/logs/luna-json.log` with a shared `2MB` `totalSizeCap`. Keeping raw AI prompts/responses out of Logback prevents AI payloads from evicting operational logs.
  - `.data/luna.db` is the SQLite WAL database shared with the `Decoupled MCP Server`; keeping multi-kilobyte trace payloads out of SQLite avoids WAL bloat and enables direct `jq` / `rg` inspection.

## Configuration, Queueing & Redaction

### Environment Variables

Parsed by [`AiTraceConfig`](../../src/main/kotlin/luna/core/ai/AiTraceConfig.kt) (documented in [`.env.example`](../../.env.example); dual-namespace `ai.trace.*` keys are also supported via `PluginContext`):

| Environment Variable | Alias | Default | Behavior |
| :--- | :--- | :--- | :--- |
| `AI_TRACE_ENABLED` | `ai.trace.enabled` | `true` | Enables JSONL trace recording (including `SKIPPED` `Participation Decision` runs). When `false`, [`JsonlAiTraceRecorder.fromConfig`](../../src/main/kotlin/luna/core/ai/AiTraceRecorder.kt#L646-L661) returns [`NoOpAiTraceRecorder`](../../src/main/kotlin/luna/core/ai/AiTraceRecorder.kt#L71-L99). |
| `AI_TRACE_DIR` | `ai.trace.dir` | `data/logs/ai` | Directory where daily `ai-YYYY-MM-DD.jsonl` files are written. |
| `AI_TRACE_MAX_DAYS` | `ai.trace.maxDays` | `14` | Maximum retention age in days (positive integer; invalid/non-positive values fall back to `14`). |
| `AI_TRACE_MAX_TOTAL_MB` | `ai.trace.maxTotalMb` | `200` | Maximum total directory size in megabytes before oldest trace files are pruned (positive integer; invalid/non-positive values fall back to `200`). |

> [!NOTE]
> Standalone constructors and unit tests default to `NoOpAiTraceRecorder` unless explicitly injected with a temporary directory recorder, ensuring `./gradlew test` does not create `data/logs/ai` in the workspace.

### Bounded Async Queue, Retention & Fail-Open Guarantees

Implemented in [`JsonlAiTraceRecorder`](../../src/main/kotlin/luna/core/ai/AiTraceRecorder.kt#L399-L663):

- **Bounded non-blocking queue**: Enqueues serialized lines into a coroutine `Channel` with capacity `256` (`DEFAULT_QUEUE_CAPACITY`) via `trySend`. A single daemon thread (`luna-ai-trace-writer`) appends lines to the daily file.
- **Fail-open behavior**: If the queue is full or closed, or if JSON serialization or filesystem writes throw, `JsonlAiTraceRecorder` emits a warning via `JsonLogger.warn(layer="AI_TRACE", component="JsonlAiTraceRecorder", ...)` and drops the trace without blocking or failing Discord event handling.
- **Exception-safe session flush & graceful shutdown**: Both [`SummaryService`](../../src/main/kotlin/luna/summarizer/SummaryService.kt) and [`ChatterService`](../../src/main/kotlin/luna/chatter/ChatterService.kt) flush `AiTraceSession.finish()` inside a `finally` block (guarded by an `AtomicBoolean` for idempotency) so timeouts and exceptions are recorded. [`LunaHost.shutdown`](../../src/main/kotlin/luna/core/Main.kt#L134-L149) calls `aiTraceRecorder.flushAndClose(timeoutMs = 5000)` to drain queued traces before JVM exit.
- **Automatic retention pruning**: `pruneRetention` runs on writer startup, on UTC day rollover, and whenever written bytes reach `(maxTotalBytes / 4).coerceIn(1L, 50 MiB)`. It deletes `ai-YYYY-MM-DD.jsonl` files older than `AI_TRACE_MAX_DAYS` and then deletes the oldest files until total size is `<= AI_TRACE_MAX_TOTAL_MB`, always sparing the currently active target file.

### Secret & Binary Redaction Rules

[`AiTraceRedactor`](../../src/main/kotlin/luna/core/ai/AiTraceRedactor.kt) scrubs every trace before serialization:

- **URL query & header secrets**: Masks `?key=`, `&api_key=`, `&apiKey=`, `x-goog-api-key`, and `Authorization: Bearer ...` values to `******` across `endpoint`, `request.payload`, `request.enabledTools`, `responseRaw`, `errorMessage`, `urlContextResults`, `googleSearchQueries`, `rawCitations`, `filteredCitations`, `finalOutput`, `errorSummary`, and `citations`.
- **Configured & session secrets**: Collects every non-blank sensitive value identified by `SecretMasker.isSensitiveKey(...)` from the unified config map plus per-session `session.registerSecret(apiKey)` calls (strings of length `>= 3`) and replaces occurrences with `******`.
- **Sensitive JSON object keys**: Any JSON object entry inside `request.payload` whose key matches `SecretMasker.isSensitiveKey(key)` or `x-goog-api-key` has its value replaced with `"******"`.
- **Binary & multimodal payloads**: Any `inlineData.data` or `inline_data.data` field in `request.payload` is replaced with `"[REDACTED_BINARY]"`. Multimodal images and videos delivered via `CDN Direct Ingestion` are stored only as `uri` (`file_uri` in legacy payloads) and `mime_type` metadata.

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
| `degraded` | `Boolean` | `true` when a fallback retry ran—currently `GeminiDecisionClient` retrying text-only input (`DECISION:text-only`) after a `DECISION:multimodal` failure. `Summary Pipeline` and `ChatterGenerator` enforce single-attempt no-fallback execution (`degraded: false`, failing directly to `FAILED` on upstream/tool/media errors). |
| `failureCategory` | `AiTraceFailureCategory?` | Normalized failure classification (`null` on clean `SUCCESS` or normal `SKIPPED`). |
| `fallbackPath` | `List<String>` | Ordered stage labels executed in this trace, formatted as `STAGE` or `STAGE:variant` (e.g. `["SUMMARY_GENERATE:url_context+grounded+lookback", "SUMMARY_TOOL_CONTINUATION:fetch_earlier_messages"]`, `["DECISION", "CHATTER_GENERATE"]`, `["DECISION:multimodal", "DECISION:text-only", "CHATTER_GENERATE"]`). |
| `totalLatencyMs` | `Long` | Wall-clock duration in milliseconds from session start to `finish()`. |
| `attemptCount` | `Int` | Number of entries in `attempts` (`attempts.size`). |
| `finalOutput` | `String?` | Final user-facing output delivered to Discord (truncated/sanitized text), or `null` when skipped/failed. |
| `citations` | `List<GroundingCitation>` | Filtered `Search Grounding` citations (`[{"title": "...", "url": "..."}]`, max 3) extracted from Interactions `model_output` `url_citation` annotations excluding `url_context_result.retrieved_url` and original slice URLs; empty list `[]` if none (always `[]` for `CHATTER`). |
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
  - `SUCCESS`: Completed on the primary path without degradation (including runs where an individual `url_context_result` entry returned non-success/`unsafe` while the model still produced valid `model_output`, in which case errors are logged via `logger.error` and preserved in `attempts[].urlContextResults`).
  - `DEGRADED_SUCCESS`: Produced output after a fallback retry (`degraded: true`), currently triggered only when `GeminiDecisionClient` succeeds on a `DECISION:text-only` retry after `DECISION:multimodal` fails. `Summary Pipeline` and `ChatterGenerator` no longer perform tool/media degraded fallback retries (`no-search`, `no-images`, `no-media`, or `baseline` retries were removed).
  - `SKIPPED`: Intentionally skipped without error (for example, `Chatter Pipeline` `Participation Decision` returned `shouldParticipate = false`, or `SummaryService` filtered the message as noise/bot).
  - `FAILED`: Terminated without producing output (single-attempt API error, safety block, timeout, parse error, missing config, etc.).
- **`AiTraceFailureCategory`**:
  - `RATE_LIMIT` — HTTP 429 rate limiting from Gemini.
  - `TIMEOUT` — Request, socket, coroutine, or agentic polling timeout.
  - `HTTP_ERROR` — Non-2xx HTTP status (other than 429) or embedded API `error` payload.
  - `SAFETY_BLOCK` — Safety `finishReason` or `error.status` (`SAFETY`, `RECITATION`, `PROHIBITED_CONTENT`, `BLOCKLIST`, `SPII`), or legacy `promptFeedback.blockReason`.
  - `PARSE_ERROR` — Invalid/empty JSON response, malformed tool call, `requires_action` without `function_call`, or missing `noul` score.
  - `EMPTY_OUTPUT` — Blank/empty `model_output` or output sanitized to blank.
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
| `endpoint` | `String?` | Target upstream URL with API keys masked (e.g. `https://generativelanguage.googleapis.com/v1beta/interactions?key=******` or `https://generativelanguage.googleapis.com/v1beta/decisions`). |
| `latencyMs` | `Long` | Elapsed duration of this attempt in milliseconds (for `AGENTIC_VIDEO`, spans submission through final polling completion). |
| `httpStatus` | `Int?` | Upstream HTTP status code (`null` on client-side timeout/network exception before response). |
| `request` | `AiAttemptRequest?` | Structured request payload and metadata (see subfields below). |
| `responseRaw` | `String?` | Raw upstream response body string (for `AGENTIC_VIDEO`, the latest submission or polling response body). |
| `finishReason` | `String?` | Response `finishReason` (e.g. `STOP`, `SAFETY`, `RECITATION`, `MAX_TOKENS`). |
| `blockReason` | `String?` | Legacy `promptFeedback.blockReason` (`null` for `/v1beta/interactions` and `/v1beta/decisions`). |
| `usageMetadata` | `GeminiUsageMetadata?` | Token accounting object (`promptTokenCount`, `candidatesTokenCount`, `totalTokenCount`, `thoughtsTokenCount`, `toolUseInputTokens`), mapped from Interactions `usage` (`input_tokens`, `output_tokens`, `total_tokens`, `thoughts_tokens`, `tool_use_input_tokens`) or `usage_metadata` when returned by Gemini. |
| `pollingAttempts` | `Int?` | Number of `GET /v1beta/interactions/{id}` polling iterations executed when `background = true` (`AGENTIC_VIDEO` only; `null` for synchronous stages). |
| `parsedOutcome` | `AiAttemptOutcome` | Attempt-level outcome: `SUCCESS`, `SKIPPED`, `TOOL_CALL`, or `ERROR`. |
| `errorMessage` | `String?` | Sanitized attempt error reason when `parsedOutcome == ERROR` (`null` otherwise). |
| `urlContextResults` | `List<UrlContextResultEntry>` | Retrieval status entries (`retrieved_url`, `status`) extracted from Interactions `url_context_result` steps (`[]` when none). Non-success or `"unsafe"` entries emit `logger.error` while preserving valid `model_output`. |
| `googleSearchQueries` | `List<String>` | Distinct Google Search queries extracted from Interactions `google_search_call` steps (`[]` when none). |
| `rawCitations` | `List<GroundingCitation>` | All `url_citation` annotations extracted from Interactions `model_output` steps prior to filtering (`[]` when none). |
| `filteredCitations` | `List<GroundingCitation>` | Deduplicated `Search Grounding` citations (max 3) remaining after excluding original slice/input URLs and `url_context_result.retrieved_url` (`[]` when none). |

#### `request` Subfields (`AiAttemptRequest`)

| Field | Type | Description |
| :--- | :--- | :--- |
| `payload` | `JsonElement?` | Full serialized request JSON (`GeminiInteractionRequest` or `GeminiDecisionRequest`) after secret and binary redaction. |
| `hasImages` | `Boolean` | `true` if `Chat Image Attachment` entries were included in this attempt. |
| `imageCount` | `Int` | Number of image attachments included in this attempt. |
| `hasVideo` | `Boolean` | `true` if a `videoUri` was included in this attempt. |
| `videoDurationSeconds` | `Long?` | Video duration in seconds when known, otherwise `null`. |
| `transcriptLength` | `Int` | Character length of the prompt/transcript input sent in this attempt. |
| `webUrlCount` | `Int` | Number of deduplicated general public web URLs (`webUrls`, capped at 20) passed for `url_context` evaluation (`0` for `DECISION`). |
| `enabledTools` | `List<String>` | Tool identifiers enabled on this attempt (e.g. `["url_context", "google_search", "fetch_earlier_messages"]`, `[]` when none). |

#### `AI Attempt` Enum Values

- **`AiAttemptStage`**:
  - `DECISION`: `POST /v1beta/decisions` (`GeminiDecisionClient`) evaluating binary `noul` participation probability (`gemini-decision-1`).
  - `SUMMARY_GENERATE`: `POST /v1beta/interactions` (`background = false`, `GeminiSummarizer` via `GeminiInteractionClient`) synchronous summary generation (no fallback retries).
  - `SUMMARY_TOOL_CONTINUATION`: `POST /v1beta/interactions` (`background = false`, `previous_interaction_id` + `function_result`) follow-up turn after fulfilling a `fetch_earlier_messages` tool call.
  - `CHATTER_GENERATE`: `POST /v1beta/interactions` (`background = false`, `ChatterGenerator` via `GeminiInteractionClient`) synchronous conversational reply generation (no fallback retries).
  - `AGENTIC_VIDEO`: `POST /v1beta/interactions` (`background = true`) + `GET /v1beta/interactions/{id}` polling (`GeminiInteractionClient`) for long videos (`> 5m`) in `Summary Pipeline` and direct-mention `Chatter Pipeline`.
- **`AiAttemptOutcome`**: `SUCCESS`, `SKIPPED`, `TOOL_CALL`, `ERROR`.

## `JsonLogger` Correlation (`data/logs/luna-json.log`)

Whenever a trace session finishes, [`AiTraceSession.finish`](../../src/main/kotlin/luna/core/ai/AiTraceRecorder.kt#L266-L332) emits one lightweight correlation event via [`JsonLogger.log`](../../src/main/kotlin/luna/core/JsonLogger.kt) into `data/logs/luna-json.log`:

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

Per-trace token breakdown (including `thoughtsTokenCount` and `toolUseInputTokens`):

```bash
jq -c '{traceId, pipeline, outcome, totalTokens: ([.attempts[]?.usageMetadata?.totalTokenCount // 0] | add), attempts: [.attempts[] | select(.usageMetadata != null) | {attemptIndex, stage, model, promptTokenCount: .usageMetadata.promptTokenCount, candidatesTokenCount: .usageMetadata.candidatesTokenCount, thoughtsTokenCount: .usageMetadata.thoughtsTokenCount, toolUseInputTokens: .usageMetadata.toolUseInputTokens, totalTokenCount: .usageMetadata.totalTokenCount}]}' data/logs/ai/ai-*.jsonl
```

Aggregate token totals across one or more JSONL files:

```bash
jq -s '{traces: length, promptTokens: ([.[].attempts[]?.usageMetadata?.promptTokenCount // 0] | add), candidatesTokens: ([.[].attempts[]?.usageMetadata?.candidatesTokenCount // 0] | add), thoughtsTokens: ([.[].attempts[]?.usageMetadata?.thoughtsTokenCount // 0] | add), toolUseInputTokens: ([.[].attempts[]?.usageMetadata?.toolUseInputTokens // 0] | add), totalTokens: ([.[].attempts[]?.usageMetadata?.totalTokenCount // 0] | add)}' data/logs/ai/ai-*.jsonl
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

### 7. Inspect `url_context` Failures, `googleSearchQueries`, and Citation Filtering

Inspect traces where any `url_context_result` entry returned a non-success or `"unsafe"` status:

```bash
jq -c 'select(any(.attempts[]?.urlContextResults[]?; (.status | ascii_downcase | IN("success", "url_retrieval_status_success") | not))) | {traceId, pipeline, outcome, urlFailures: [.attempts[] | {attemptIndex, stage, webUrlCount: .request.webUrlCount, urlContextResults}]}' data/logs/ai/ai-*.jsonl
```

Inspect enabled tools, search queries, and `rawCitations` vs. `filteredCitations`:

```bash
jq -c 'select(any(.attempts[]?; (.request.enabledTools | length) > 0)) | {traceId, pipeline, outcome, citations, attempts: [.attempts[] | {attemptIndex, stage, webUrlCount: .request.webUrlCount, enabledTools: .request.enabledTools, urlContextResults, googleSearchQueries, rawCitations, filteredCitations}]}' data/logs/ai/ai-*.jsonl
```
