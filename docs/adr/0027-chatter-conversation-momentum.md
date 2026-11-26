# Chatter Conversation Momentum for Natural Back-and-Forth Exchanges

Status: accepted (amended by [ADR 0029](0029-chatter-ambient-fatigue-and-anti-hijack-gating.md))

## Context

Under [ADR 0015](0015-chatter-humanization-debounce-and-silent-reactions.md), `ChatterNoiseFilter.evaluate` (`ChatterNoiseFilter.kt:146`) unconditionally rejects non-mention ambient messages while a channel is within `CHATTER_COOLDOWN_SECONDS` (default 5s) after Luna delivers a reply (`ChatterService.kt:556`). In live Discord conversations, once Luna answers a `@mention` or joins an active topic and someone responds 2–3 seconds later without `@mentioning` her again, a flat 5-second lockout causes Luna to abruptly drop out of an ongoing exchange. Conversely, removing the cooldown altogether risks simultaneous-message double triggers and spammy back-to-back domination in busy channels.

> _Amends [ADR 0015](0015-chatter-humanization-debounce-and-silent-reactions.md) Section 1 (`CHATTER_COOLDOWN_SECONDS` ambient pre-filtering in `ChatterNoiseFilter`)._

## Decision

1. **Per-Channel In-Memory `Conversation Momentum` Window (Bounded LRU)**:
   - Track `Conversation Momentum` per Discord channel (not per `(channel, author)`) in a bounded LRU in-memory cache (default max capacity 500, injectable `timeSource: () -> Long`, zero database persistence) matching `BoundedChannelCooldownCache` and `CODING_STANDARDS.md` §1.
   - Channel-level scoping preserves natural multi-party group banter (e.g., User A speaks -> Luna replies -> User B chimes in 3 seconds later on the same topic), while relying on `Participation Decision` over `Working Memory Window` to filter unrelated topic shifts.

2. **Minimal Anti-Spam Cooldown Floor**:
   - While a channel has active, non-exhausted `Conversation Momentum` (within `CHATTER_MOMENTUM_WINDOW_SECONDS`, default `30`s), soften `ChatterNoiseFilter.evaluate`'s ambient cooldown check from `CHATTER_COOLDOWN_SECONDS` down to `min(momentumCooldownFloorMs, cooldownDurationMs)` (default floor `1500`ms).
   - Keeping a short anti-spam floor prevents instant re-triggering on messages that crossed in flight during delivery while letting 2–3s follow-ups enter the existing `Burst Debounce Window` and `Participation Decision`. Clamping with `min(..., cooldownDurationMs)` guarantees momentum never lengthens cooldown when operators configure a shorter or zero `CHATTER_COOLDOWN_SECONDS`.

3. **Consecutive Ambient Turn Cap, Counting Boundary, and Exhaustion Reset**:
   - Cap rapid back-and-forth participation at `CHATTER_MOMENTUM_MAX_TURNS` (default `3`) consecutive ambient turns within a momentum window:
     - **Direct `@mention` / reply-to-Luna delivery**: starts/refreshes the momentum window and resets `ambientTurnCount = 0` (granting up to 3 subsequent ambient momentum turns).
     - **Ambient text reply delivery**: starts/refreshes the momentum window and sets `ambientTurnCount = 1` on a cold start, or increments `ambientTurnCount + 1` while the window is active.
     - **`Silent Reaction Path` and `Deferred Catch-Up`**: neither start nor extend `Conversation Momentum` (passive reactions do not join text dialogue, and backlog catch-up is not a live exchange; `DeferredCatchUpDeliveryExecutor` continues recording normal cooldown only).
     - **Delivery-gated recording**: update momentum state only after `deliveryEngine.deliverSegmentedReply` succeeds (cancelled, skipped, or failed runs never start or increment momentum).
   - **Exhaustion behavior**: once `ambientTurnCount >= maxTurns`, momentum pauses and normal full `CHATTER_COOLDOWN_SECONDS` re-engages (without an extra penalty multiplier). If Luna delivers another ambient reply after waiting out the normal cooldown while still inside the 30s window, extend the window timestamp while keeping `ambientTurnCount` at or above the cap; momentum resets only after a full `windowSeconds` of silence from Luna or upon a new direct `@mention` reply. This prevents a "3 rapid replies -> 5s pause -> 3 rapid replies" loop.

4. **Affinity-Guarded `Participation Decision` Threshold Boost**:
   - While `Conversation Momentum` is active and non-exhausted, layer a bounded threshold reduction (`-CHATTER_MOMENTUM_THRESHOLD_BOOST`, default `0.10`, clamped within existing threshold bounds) onto `effectiveThreshold` alongside `Circadian Persona Drift` and `Affinity Tier` (`ChatterService.kt:246-260`) so brief follow-ups ("哈哈真的假的", "然後呢？") are less likely to be dropped.
   - **Low-affinity guard**: suppress the momentum threshold boost when the triggering author's `Affinity Tier` is `COOL` or `DISLIKED` (keeping only channel-level cooldown softening so the higher low-affinity threshold still governs participation), preventing Luna from becoming overly eager toward disliked users simply because she was chatting with someone else in the channel.
   - Keep `reactionThreshold` unchanged so active back-and-forth exchanges naturally favor text replies over `Silent Reaction Path` when `effectiveThreshold` drops.

5. **Strict Optional Configuration & `AI Trace` Observability**:
   - Expose all four parameters as optional `CHATTER_MOMENTUM_*` environment variables parsed via strict `EnvReader` (per [ADR 0018](0018-unified-strict-env-reader-configuration.md)) with safe defaults and bounds: `CHATTER_MOMENTUM_WINDOW_SECONDS` (default `30`, `0` disables), `CHATTER_MOMENTUM_COOLDOWN_FLOOR_MS` (default `1500`), `CHATTER_MOMENTUM_MAX_TURNS` (default `3`, `0` disables), and `CHATTER_MOMENTUM_THRESHOLD_BOOST` (default `0.10`, `0.0` disables boost only).
   - Record momentum state (active flag, current ambient turn count, and applied threshold offset) in `AI Trace` for observability parity with `Circadian Persona Drift` and `Affinity Tier`.

## Consequences

- **Positive**: Luna stays naturally responsive during multi-turn Discord back-and-forth exchanges—especially immediately after a `@mention`—without requiring repeated pings; the consecutive turn cap and exhaustion window prevent spam loops; low-affinity users cannot piggyback on another speaker's momentum for easier participation; zero extra LLM calls or database I/O are introduced.
- **Negative / Trade-offs**: Per-channel scoping means an unrelated bystander posting 2 seconds after Luna's reply still incurs a `Participation Decision` call (mitigated by `Burst Debounce Window` coalescing and the low-latency decision model); four additional optional `CHATTER_MOMENTUM_*` settings expand `.env.example` slightly in exchange for live tuning without redeployment.
