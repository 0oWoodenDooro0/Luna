# Chatter Custom Emoji Semantics and Rolling Usage Ranking

Status: accepted (amended by [ADR 0019](0019-chatter-application-emojis-and-persona.md))

## Context

Under ADR-0015 (`Guild Custom Emoji Policy` and `Silent Reaction Path`), `Chatter Pipeline` injects up to 50 guild custom emojis into reply and reaction prompts as raw Discord mention tokens (`<:name:id>` / `<a:name:id>`) truncated in arbitrary order. Because custom emoji names are frequently opaque or server-specific (e.g., `a1`, `blob3`), `Chatter Pipeline` cannot reliably interpret custom emojis appearing in incoming chat—including external Nitro emojis from other servers—nor choose contextually fitting guild emojis when replying or reacting silently.

## Decision

1. **Global `Custom Emoji Meaning` Cache via Opt-In Vision LLM (Scoped to `luna.chatter`)**:
   - Keep the emoji meaning cache, vision resolver, and usage tracker scoped inside `luna.chatter` (e.g., `luna.chatter.emoji`), backed by SQLite tables in `PluginContext.database` keyed globally by Discord `emoji_id` so the schema stays clean for future cross-plugin extraction.
   - Generate concise Traditional Chinese semantic descriptions (approx. 5–15 characters) automatically via Gemini using `CDN Direct Ingestion` (`cdn.discordapp.com/emojis/{id}`), passing the emoji `name` as an auxiliary hint while instructing the model to prioritize visual content and ignore meaningless names.
   - Reserve a manual-override column in the schema for future administrative editing, while omitting manual edit slash commands from the initial release.
   - Gate automatic vision resolution behind a dedicated optional environment variable `CHATTER_EMOJI_VISION_MODEL` with **no fallback** to `CHATTER_GEMINI_MODEL` or `GEMINI_MODEL`. When unset or blank, automatic vision calls are disabled (existing cached meanings are still read; uncached emojis degrade to raw `<:name:id>`).

2. **Animated Fallback, Rename Preservation, and Failure Cooldown**:
   - For animated custom emojis (`<a:name:id>`), send `.gif` (`image/gif`) first so motion-dependent semantics are preserved, falling back automatically to `.png` (static first frame) if vision resolution fails.
   - When a guild renames an existing `emoji_id`, update the stored name metadata without re-running vision resolution (the underlying image is immutable per ID).
   - Record a failure cooldown (e.g., 1 hour) per `emoji_id` when vision resolution fails (network error, rate limit, unreadable asset) to prevent repeated blocking retries on every message.

3. **Hybrid On-Demand Blocking + Background Pre-Scan with Guardrails**:
   - Pre-scan current guild emojis asynchronously on startup and on `EmojisUpdateEvent`.
   - Before assembling prompts, resolve any uncached custom emojis referenced in the dialogue transcript (including external Nitro emojis) and any remaining uncached entries in the prompt's guild emoji pool synchronously in parallel, bounded by strict guardrails (e.g., max concurrency 5 and a 5-second total round timeout). Timed-out or failed lookups degrade gracefully to raw `<:name:id>` without aborting the reply.
   - External Nitro emojis are resolved for **comprehension only** and are never added to `allowedCustomEmojiIds` for reply generation or `Silent Reaction Path`.

4. **Dual Prompt Injection (Transcript + Selection Pool)**:
   - Annotate custom emojis that have a cached `Custom Emoji Meaning` inline in dialogue transcripts as `<:name:id>(意思)` so both `Participation Decision` (`GeminiDecisionClient`) and reply/reaction generation understand incoming messages.
   - Format the bounded guild emoji selection pool in reply and `Silent Reaction Path` prompts as `<:name:id>：意思` (falling back to bare `<:name:id>` when uncached).

5. **30-Day Rolling `Emoji Usage Ranking` for the 50-Emoji Cap**:
   - Retain the 50-emoji prompt budget (`MAX_PROMPT_GUILD_EMOJIS`), replacing arbitrary truncation with per-guild `Emoji Usage Ranking`.
   - Count custom emoji occurrences in both guild messages and reactions (`MessageCreateEvent` and reaction-add events), aggregated daily in SQLite by `(guild_id, emoji_id, day)` over a rolling 30-day window with periodic cleanup of expired rows.
   - Select the top 50 guild emojis by 30-day usage count; when fewer than 50 have recent usage, backfill deterministically by emoji name/ID.

## Consequences

- **Positive**: `Participation Decision`, reply generation, and `Silent Reaction Path` all gain visual-semantic understanding of both guild and external custom emojis with zero manual curation; opt-in `CHATTER_EMOJI_VISION_MODEL` prevents unexpected vision token spend; 30-day rolling `Emoji Usage Ranking` keeps large servers' active meme vocabulary inside the 50-emoji prompt budget.
- **Negative / Trade-offs**: First-time encounters with uncached emojis add bounded latency (up to the 5-second guardrail) before prompt assembly; adding `<:name:id>：意思` increases prompt size by roughly 500–1,000 tokens for a full 50-emoji list; server-specific inside jokes that cannot be inferred from visual appearance + name will remain approximate until manual override commands are added in a later iteration.
