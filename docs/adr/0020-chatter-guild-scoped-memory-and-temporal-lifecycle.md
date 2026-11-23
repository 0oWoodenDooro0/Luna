# Chatter Guild-Scoped Memory, Fact Expiration, and Retrieval Temporal Awareness

Status: accepted

## Context

Under [ADR 0017](0017-chatter-layered-memory-and-agentic-retrieval.md), `Chatter Pipeline` partitioned shared `Long-Term Memory` by `channel_id` (`scope = 'channel'`), enforced no time-based expiration (`expires_at`) on ephemeral events, reconciled new facts against only Top-3 similar memories without current-date context, ranked retrieval results with age-blind RRF while returning raw epoch-millisecond timestamps to the model, and exposed an unpaginated `/memory list` plus single-item `/memory delete` with no bulk clear. In practice, channel-scoped isolation fragmented server-wide jokes and community context across channels, temporary plans (e.g., "interview tomorrow", "traveling this week") persisted indefinitely until evicted by LRU, and users with dozens of stored facts could not view truncated entries or clear their memory in one step.

> _Amends [ADR 0017](0017-chatter-layered-memory-and-agentic-retrieval.md) Sections 1, 3, 4, 5, and 6._

## Decision

1. **Guild-Scoped Shared Memory (`channel` → `guild`)**:
   - Replace `channel` memory scope with `guild` memory scope (`scope IN ('user', 'guild')`). Both `user` memories (keyed by `(guild_id, target_user_id)`) and `guild` memories (keyed by `guild_id`) are shared across all Chatter-enabled channels within the same Discord guild and strictly isolated across guilds.
   - Keep `Distillation Cursor` (`chatter_memory_cursors`) per-channel because Discord message streams are fetched per channel via Kord.
   - Migrate existing SQLite databases idempotently: recreate `chatter_memories` with `scope CHECK(scope IN ('user', 'guild'))` and nullable `expires_at INTEGER`, drop `channel_id` isolation on memories, convert existing `'channel'` rows to `'guild'`, and apply LRU eviction against the new guild cap.
   - Replace `CHATTER_MEMORY_MAX_PER_CHANNEL` with `CHATTER_MEMORY_MAX_PER_GUILD` (default **300**), retaining `CHATTER_MEMORY_MAX_PER_USER` (default **100**). Update `search_long_term_memory` `scope` parameter values to `user | guild | both`.

2. **Semantic `Fact Expiration` and Dual-Layer Purge (No Blanket Inactivity TTL)**:
   - Retain LRU eviction (no blanket inactivity TTL) for durable `Atomic Fact` records such as user preferences and standing background facts.
   - Add an optional `expires_at` (epoch ms) column for time-bound events. During `Memory Distillation`, inject the current ISO-8601 date and day of week into the extraction prompt and instruct the model to output an optional `expires_at` (`YYYY-MM-DD`, or `null` for durable facts). Clamp parsed dates in code to a **1–365 day** guardrail window from distillation time, degrading invalid or unparseable values safely to `null`.
   - Enforce expiration on two layers without adding a separate scheduler thread:
     1. **Read-time filtering**: `search` and `listUserMemories` always exclude rows where `expires_at IS NOT NULL AND expires_at <= now`.
     2. **Piggybacked background hard delete**: `MemoryDistillationCoordinator.tick()` periodically hard-deletes expired rows synchronously across `chatter_memories`, `chatter_memories_vec`, and `FTS5`.

3. **Distillation Quality and Temporal-Aware `Agentic Memory Retrieval`**:
   - Widen `Memory Distillation` reconciliation similarity lookup from Top-3 to **Top-6** and deduplicate near-identical candidates within the same distillation batch before reconciliation so multi-fact topics do not accumulate stale contradictions.
   - Keep `search_long_term_memory` strictly **read-only** in `ChatterGenerator` (no conversational `forget_user_memory` tool) to eliminate prompt-injection deletion risk and preserve reply latency; natural-language corrections continue to flow through background `Memory Distillation` `DELETE` reconciliation and self-service `/memory` commands.
   - Apply bounded **`Recency Decay`** on top of hybrid RRF fusion (`finalScore = rrfScore * (0.7 + 0.3 * exp(-ageDays / 60.0))`) so recent facts outrank stale ones at comparable relevance while the `0.7` floor keeps high-relevance durable preferences discoverable.
   - Format retrieved memory timestamps for the model as human-readable calendar dates with relative age (e.g., `2026-11-20 (3d ago)`) and include `expires_at` when present.

4. **Self-Service `/memory` UX (No Admin Commands)**:
   - Keep `/memory` strictly self-service and ephemeral for the invoking user's own `user` memories in the current guild; omit admin commands for `guild` or cross-user memory management.
   - Expand `/memory` to three stateless subcommands:
     - `/memory list [page]`: paginated at **10 memories per page** with page/total-page metadata and expiry dates when present.
     - `/memory delete <id>`: unchanged single-item hard delete.
     - `/memory clear confirm:true`: bulk hard delete of all `user` memories owned by the invoking user in the current guild, gated by a required boolean `confirm` option (`true`) to avoid stateful Discord component listeners.

## Consequences

- **Positive**: Shared server jokes, norms, and events carry naturally across channels within a guild; ephemeral plans expire automatically without polluting long-term preferences; wider reconciliation and batch deduplication reduce duplicate/stale facts; users can browse all stored personal memories via pagination and wipe them in one command; keeping reply-time tools read-only and omitting admin commands keeps the privacy and security surface small.
- **Negative / Trade-offs**: Distinct channels in the same guild can no longer keep isolated `channel`-only shared memories; `guild` memories have no slash command for manual inspection or deletion and rely on background distillation `DELETE`, `Fact Expiration`, and guild-wide LRU eviction; existing deployments undergo a one-time SQLite table-rebuild migration on startup.
