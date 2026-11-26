# Chatter Memory Ranking Enhancements and Social Understanding Layer

Status: accepted (amended by [ADR 0025](0025-chatter-user-affinity-and-selective-response.md))

## Context

Under [ADR 0017](0017-chatter-layered-memory-and-agentic-retrieval.md) and [ADR 0020](0020-chatter-guild-scoped-memory-and-temporal-lifecycle.md), `Long-Term Memory` stores only flat `Atomic Fact` rows in `chatter_memories`, ranks them with `updated_at`-only `Recency Decay` (ignoring `last_accessed_at` outside LRU eviction), indexes raw fact text without explicit retrieval keywords, treats all facts as equally important during ranking and LRU eviction, and injects zero memory into replies unless the model decides to call `search_long_term_memory`. In practice, casual banter rarely triggers a tool call—so Luna lacks immediate familiarity with recurring speakers' communication styles and guild humor—while frequently recalled core facts still decay at the same rate as forgotten trivia.

> _Amends [ADR 0017](0017-chatter-layered-memory-and-agentic-retrieval.md) Sections 3, 4, 5, and 6, and [ADR 0020](0020-chatter-guild-scoped-memory-and-temporal-lifecycle.md) Sections 2, 3, and 4._

## Decision

1. **Dual-Table `Long-Term Memory` (`chatter_memories` + `chatter_understandings`)**:
   - Retain `chatter_memories` for `Atomic Fact` records backed by `sqlite-vec` (`chatter_memories_vec`) and `FTS5 trigram` (`chatter_memories_fts`). Add `importance` (`INTEGER NOT NULL DEFAULT 3`, range `1..5`) and `keywords` (`TEXT NOT NULL DEFAULT ''`, 3–6 short retrieval anchor terms).
   - Index `content` + `keywords` in both `Atomic Fact` embeddings and `chatter_memories_fts`, while omitting `keywords` from `search_long_term_memory` tool responses to conserve reply tokens.
   - Add a separate pure-relational table `chatter_understandings` (`id`, `guild_id`, `scope IN ('user', 'guild')`, `target_user_id`, `subject`, `content`, `created_at`, `updated_at`, `last_accessed_at`) for `Social Understanding` records. Omit `vec0`, `FTS5`, `keywords`, and `expires_at` on `chatter_understandings` to keep GCP `e2-micro` memory overhead and embedding API costs minimal.
   - Cap `chatter_understandings` at **15 per user** and **20 per guild** (configurable via `EnvReader`), evicting overflow via `MAX(updated_at, last_accessed_at)` LRU hard delete.
   - Migrate existing `chatter_memories` tables idempotently (defaulting `importance = 3` and `keywords = ''`) and detect `chatter_memories_vec` embedding dimension mismatches on startup.

2. **Dual-Signal `Recency Decay`, Importance Weighting, and Importance-Aware Eviction**:
   - Upgrade `Recency Decay` in `ChatterMemoryRrfRanker` (applied to `Atomic Fact` only; `Social Understanding` is exempt) to blend last-updated age and last-recalled age: `blendedRecency = 0.7 * exp(-updatedAgeDays / 60.0) + 0.3 * exp(-accessedAgeDays / 60.0)`, preserving the `0.7` floor (`decayMultiplier = 0.7 + 0.3 * blendedRecency`) so frequently recalled memories stay fresh.
   - Apply a mild `importance` multiplier (`0.85..1.15` mapped linearly from `1..5`) on top of RRF × `Recency Decay` so semantic/lexical relevance remains dominant.
   - Upgrade `chatter_memories` LRU overflow eviction to evict lower-importance stale facts before high-importance (`>= 4`) core facts.

3. **Zero-Embedding `Understanding Pre-Injection` and Tool Augmentation**:
   - Introduce `Understanding Pre-Injection` in `ChatterGenerator`: before reply generation, fetch compact `Social Understanding` entries directly from SQLite (zero embedding calls, sub-millisecond local read) for the triggering author (up to 10), up to 2 other recent human speakers in the `Working Memory Window` (up to 5 each), and the active guild (up to 8), bounded by a total soft character budget (~1,500 chars).
   - Keep `Atomic Fact` strictly behind `Agentic Memory Retrieval` (`search_long_term_memory`), and keep `Participation Decision` free of any memory injection.
   - When `search_long_term_memory` targets a specific `target_user_id` (e.g., an off-screen user mentioned in chat), attach that user's `Social Understanding` rows via direct SQL alongside ranked `Atomic Fact` hits.

4. **Three-Stage `Memory Distillation` with Fail-Open `UnderstandingPatch`**:
   - Extend Stage 1 (`extract`) and Stage 2 (`reconcile`) on `gemini-3.5-flash-lite` to extract and preserve `importance` (1–5, anchored by a 5-point rubric) and `keywords` (3–6 short terms) for `Atomic Fact` records.
   - Add a dedicated Stage 3 (`UnderstandingPatch`, `gemini-3.5-flash-lite`) that runs after `Atomic Fact` reconciliation and `Distillation Cursor` persistence for the batch's human speakers and guild. Pass existing `Social Understanding` entries with short prompt aliases (`[u1]`, `[u2]`) and support `ADD`, `UPDATE`, `REAFFIRM` (timestamp-only touch), `DELETE`, or empty no-op.
   - Isolate Stage 3 as fail-open (logged to `AI Trace`) so an `UnderstandingPatch` failure never rolls back persisted `Atomic Fact` mutations or stalls `Distillation Cursor`.

5. **Transparent Self-Service `/memory` UX Across Both Tables**:
   - Keep `/memory` strictly ephemeral and self-service for the invoking user's own `user`-scoped entries in the current guild.
   - Update `/memory list [page]` to list both `Atomic Fact` (`[事實 #<id>]`) and `Social Understanding` (`[印象 #<id>]`) entries.
   - Keep `/memory delete <id>` as an integer ID and add an optional `kind` choice (`fact` default | `understanding`) to disambiguate independent table primary keys.
   - Update `/memory clear confirm:true` to hard-delete both `chatter_memories` and `chatter_understandings` owned by the invoking user in the current guild.

## Consequences

- **Positive**: Luna gains immediate familiarity with active speakers and guild norms during casual banter at zero embedding latency; frequently recalled or high-importance `Atomic Fact` records resist decay and premature LRU eviction; explicit `keywords` improve CJK/slang recall without bloating reply prompts; keeping `chatter_understandings` pure-relational avoids extra `vec0`/`FTS5` RAM overhead on `e2-micro`; users retain full visibility and control over both facts and impressions via `/memory`.
- **Negative / Trade-offs**: Each `Memory Distillation` batch adds one lightweight `gemini-3.5-flash-lite` call for `UnderstandingPatch`; `ChatterGenerator` prompts grow by up to ~1,500 characters when active speakers have accumulated `Social Understanding` entries; `Social Understanding` cannot be semantically searched across large guild pools without later adding a dedicated index; `/memory delete` requires selecting `kind:understanding` to delete an impression rather than a fact.
