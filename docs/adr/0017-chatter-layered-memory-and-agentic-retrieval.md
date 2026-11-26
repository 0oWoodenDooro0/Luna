# Chatter Layered Memory and Agentic Retrieval

Status: accepted (amended by [ADR 0020](0020-chatter-guild-scoped-memory-and-temporal-lifecycle.md), [ADR 0022](0022-chatter-memory-ranking-and-social-understanding.md))

## Context

`Chatter Pipeline` currently fetches a fixed 15-message window via Kord (`getMessagesBefore`) with no recency boundary, strips third-party bot messages via `Dialogue Noise Filter`, and has no persistent memory across sessions. As a result, replies can either pull stale multi-day-old messages into immediate dialogue context or lack awareness of user preferences, channel norms, and past events once they scroll past 15 messages. At the same time, Luna targets low-memory deployments such as GCP `e2-micro` (1 GB RAM), making unconditional prompt pre-injection, raw Discord log mirroring, or heavy JVM NLP/tokenization libraries impractical.

> _Refines [ADR 0010](0010-gemini-decision-and-multimodal-chatter.md) Section 2 (fixed 15-message timeless history window and third-party bot filtering in `Chatter Pipeline`), replacing the timeless window with a recency-bounded `Working Memory Window` and making third-party bot messages visible in Chatter context without allowing bots to trigger replies._

## Decision

1. **Module Boundary, Guild Isolation, and Channel Scope**:
   - Scope `Long-Term Memory`, `Memory Distillation`, and `Agentic Memory Retrieval` inside `Chatter Pipeline` behind a clean internal storage/retrieval seam so it can be extracted into a `Shared Library` later if `Summary Pipeline` or `Decoupled MCP Server` needs access.
   - Strictly isolate all `user` and `channel` memories by `guild_id`; `user` memories are shared across all Chatter-enabled channels within the same guild (keyed by `(guild_id, target_user_id)`), and are never shared across guilds.
   - Operate `Memory Distillation` and retrieval exclusively on Chatter-enabled channels. On cold start (no `Distillation Cursor` for a channel), initialize the cursor from the first observed message without backfilling historical channel logs.

2. **Recency-Bounded `Working Memory Window` and Bot Visibility**:
   - Replace the timeless 15-message Chatter history window with a dual-bounded `Working Memory Window`: default **10-minute recency window**, capped at **30 messages** for reply generation and **15 messages** for `Participation Decision` (both configurable via environment variables). Preserve `Reply Reference Chaining` when a message replies to an older message beyond the 10-minute boundary.
   - Stop filtering third-party bot messages out of `Chatter Pipeline` transcripts (Decision, Reply, and `Memory Distillation`). Tag speaker headers with `[BOT]` and Discord User ID (e.g., `Alice (uid:123456): ...`, `NewsBot [BOT] (uid:789012): ...`) while continuing to filter Discord system events.
   - Keep bot-loop protection intact: third-party bot messages never trigger `Participation Decision` or Chatter replies, and `Memory Distillation` message-count thresholds count **human messages only**.

3. **Cursor-Only Background `Memory Distillation` (No Raw Chat Log Table, No Hot-Path Write Tool)**:
   - Do not mirror raw Discord chat logs into SQLite. Persist only a per-channel `Distillation Cursor` (`channel_id -> last_summarized_message_id`) while tracking last activity time and unprocessed human message count in memory.
   - Trigger `Memory Distillation` via a dual rule: (a) channel quiet for **5 minutes** with **>= 5 unprocessed human messages**, or (b) forced batch flush at **50 unprocessed messages** or **30 minutes** since the oldest unprocessed message. Fetch the batch on demand via Kord `getMessagesAfter(last_summarized_message_id)`.
   - Omit an inline reply-time write tool (`save_important_memory`) from the initial design; all `Long-Term Memory` writes flow through asynchronous `Memory Distillation` to keep reply latency low and reconciliation centralized.
   - Distill `Atomic Fact` records using `gemini-3.5-flash-lite` (without unsupported sampling parameters `temperature`/`topP`/`topK`), preserving Luna's own replies in the input transcript for Q&A context while instructing the model to extract only objective user/channel facts.
   - Embed distilled facts in batches via the Google Gemini Embedding API (`gemini-embedding-001`, reusing `GEMINI_API_KEY` and `GEMINI_BASE_URL`, `batchEmbedContents`, `taskType="RETRIEVAL_DOCUMENT"`, `outputDimensionality=768`) with mandatory **client-side L2 normalization** (required for Matryoshka truncation below 3072 dimensions). For each candidate fact, search Top-3 similar existing memories and invoke the LLM to reconcile via `INSERT`, `UPDATE`, or `DELETE`.

4. **Hybrid SQLite Storage (`sqlite-vec` + `FTS5`) and LRU Lifecycle**:
   - Store memories in Luna's SQLite database (`xerial/sqlite-jdbc` on Ubuntu Jammy glibc) using `sqlite-vec` for 768-dimensional cosine vector search and built-in `FTS5` with `tokenize='trigram'` for Traditional Chinese / CJK substring matching (supplementing queries shorter than 3 characters with `LIKE` or falling back to vector-only search to avoid heavy JVM tokenizers).
   - Enforce no time-based TTL on `Long-Term Memory` (preserving durable user preferences). Instead, cap capacity per guild with an **LRU eviction policy** (default **100 memories per user**, **200 memories per channel**, evicting least-recently updated/accessed entries). All deletions (`DELETE` reconciliation, LRU eviction, `/memory delete`) are **hard deletes** that synchronously remove entries from the base table, `sqlite-vec`, and `FTS5`.

5. **`Agentic Memory Retrieval` (`search_long_term_memory`) and Three-Layer Guardrails**:
   - Keep `Participation Decision` (`gemini-decision-1`) free of `Long-Term Memory` injection so ambient gating remains zero-query-cost.
   - Expose `search_long_term_memory(query, limit, scope, target_user_id)` alongside existing `google_search` and `url_context` tools in `ChatterGenerator` (`/v1beta/interactions`). Support `limit` in `1..8` and `scope` in `user | channel | both` (defaulting `target_user_id` to the triggering message author when `scope="both"` omits it).
   - Embed queries with `taskType="RETRIEVAL_QUERY"` (768d, L2-normalized), combine `sqlite-vec` and `FTS5` via Reciprocal Rank Fusion (RRF), and exclude rows whose `updated_at` falls within the last **10 minutes** (configurable) to align with the `Working Memory Window` without duplication.
   - Enforce three guardrails:
     1. **Turn and Call Caps**: Raise `ChatterGenerator` Interactions API `Max Turns` to **8** and cap `search_long_term_memory` at **5 calls per reply** (returning a polite cap-reached notice instead of throwing so the model completes its reply).
     2. **Dynamic Score Cutoff**: Filter out candidates below the cosine similarity threshold (default `0.55`, configurable) unless matched by `FTS5`, returning only qualifying memories with an explanatory note rather than padding to `limit`.
     3. **Timeout and Fail-Open Degradation**: Bound each tool call to **3 seconds** and degrade gracefully on any SQLite or Embedding API error so `Chatter Pipeline` always completes its reply.

6. **Privacy Commands, `AI Trace`, and GCP `e2-micro` Resource Optimization**:
   - Provide `/memory list` and `/memory delete <id>` slash commands with **ephemeral** replies scoped strictly to the invoking user's own `user` memories within the current guild (admin cross-user / `channel` memory management commands are deferred).
   - Record `Agentic Memory Retrieval` tool calls and background `Memory Distillation` runs in the existing daily JSONL `AI Trace` (`data/logs/ai/`).
   - Package the architecture-matched (`amd64`/`arm64`) `sqlite-vec` shared library in `Dockerfile`, set default `JAVA_TOOL_OPTIONS="-Xmx384m -XX:+UseSerialGC"` (overridable at runtime), configure SQLite with `PRAGMA cache_size = -8000;` and `PRAGMA mmap_size = 0;`, and document host-level 2 GB Swap setup in deployment docs.

## Consequences

- **Positive**: Ambient small talk incurs zero memory-query latency or embedding cost; aligning the 10-minute `Working Memory Window` with the 10-minute `Long-Term Memory` recency filter eliminates context duplication and stale-history pollution; cursor-only distillation avoids raw Discord message retention and deletion-sync complexity; native `sqlite-vec` + `FTS5 trigram` fits comfortably within a 384 MB JVM heap on GCP `e2-micro`.
- **Negative / Trade-offs**: New facts stated in chat become searchable in `Long-Term Memory` only after `Memory Distillation` runs (up to 5–30 minutes later, though they remain in the `Working Memory Window` for 10 minutes); `FTS5 trigram` increases index size relative to `unicode61` and requires fallback handling for 1–2 character queries; `Dockerfile` gains a native binary dependency (`sqlite-vec` `.so`) that must match the container libc (`glibc`) and CPU architecture.
