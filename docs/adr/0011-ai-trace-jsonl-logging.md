# Dedicated JSONL AI Trace Logging Outside JsonLogger and SQLite

Status: accepted

## Context

Luna invokes Google Gemini across two isolated `Luna Plugin` pipelines (`Summary Pipeline` via `GeminiSummarizer` and `Chatter Pipeline` via `GeminiDecisionClient` and `ChatterGenerator`), plus shared `Agentic Video Understanding` in `GeminiInteractionClient`. A single Discord trigger can produce multiple upstream AI HTTP exchanges due to `Participation Decision` checks, `fetch_earlier_messages` multi-turn tool continuations, and sequential degraded fallbacks (stripping `googleSearch` or multimodal `file_data`).

Existing logging relies on `JsonLogger` backed by Logback `LogstashEncoder` writing to `data/logs/luna-json.log` with a 2 MB `totalSizeCap` shared across all system, Kord, and Ktor events, while `.data/luna.db` uses SQLite WAL shared with the `Decoupled MCP Server`. Neither captured raw AI inputs/outputs, and searching across multi-step fallbacks or reading full prompts from SQLite or Logstash-wrapped general logs is cumbersome for automated AI debugging tools.

## Decision

1. **Dedicated `AI Trace` JSONL Store (`data/logs/ai/ai-YYYY-MM-DD.jsonl`)**:
   - Introduce an independent, non-blocking `AiTraceRecorder` in `luna.core.ai` that writes pure JSONL files under `data/logs/ai/` (covered by the existing `./data:/app/data` Docker volume mount and `.gitignore`), separate from Logback's 2 MB `luna-json.log` rotation and SQLite `.data/luna.db`.
   - Emit a lightweight correlation reference (`traceId`, `outcome`, `degraded`) to `JsonLogger` so general operational logs and AI Traces cross-reference cleanly.

2. **One Aggregated JSONL Line per `AI Trace`**:
   - Flush one self-contained `AI Trace` line per Discord-triggered AI workflow execution (in a `finally` block so timeouts and exceptions are still persisted), containing top-level diagnostic index fields (`traceId`, `pipeline`, `trigger`, `discord`, `outcome`, `degraded`, `failureCategory`, `fallbackPath`, `totalLatencyMs`, `finalOutput`, `errorSummary`) alongside the chronologically ordered `attempts[]` array of every `AI Attempt` (with `Agentic Video Understanding` polling collapsed into a single attempt summary).
   - Store full untruncated prompts, tool declarations, and raw upstream response/error payloads while keeping multimodal inputs as URI + MIME metadata only and redacting all API keys and secrets.

3. **Always-On with Bounded Disk Retention**:
   - Enable `AI Trace` recording by default (`AI_TRACE_ENABLED=true`, including `SKIPPED` participation decisions) with automatic daily rotation and bounded retention (`AI_TRACE_MAX_DAYS=14`, `AI_TRACE_MAX_TOTAL_MB=200`).

## Considered Options

- **Reuse `JsonLogger` (`luna-json.log`)**: Rejected because Logstash envelope fields add noise, raising its cap affects all Kord/Ktor/system logs, and AI multi-attempt correlations would interleave across concurrent Discord channels.
- **SQLite table in `.data/luna.db`**: Rejected because multi-kilobyte prompt/response payloads bloat the WAL database shared with `:mcp-server` and require SQL queries instead of direct `rg` / `jq` / `view_file` inspection by AI debugging agents.
- **Per-attempt streaming JSONL lines**: Rejected because concurrent Discord channel traffic interleaves lines from unrelated workflows, forcing AI readers to stitch traces across lines before diagnosing fallback behavior.

## Consequences

- **Positive**: AI coding/debugging agents can filter incidents immediately with `jq` or `rg` on top-level diagnostic fields (`outcome`, `degraded`, `failureCategory`) and read the entire request/fallback/response story in a single line without touching SQLite or parsing noisy system logs.
- **Negative / Trade-offs**: Unfinished traces in flight during an ungraceful `SIGKILL`/OOM process termination are not flushed; accepted given bounded HTTP request timeouts and `finally`-block flushing on normal errors/timeouts.
