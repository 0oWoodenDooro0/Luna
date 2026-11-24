# Persona-Driven Daily Schedule, Presence Sync, and Deferred Catch-Up

Status: accepted

## Context

Under ADR-0015 and ADR-0019, `Chatter Pipeline` modulates Luna's conversational tone and `Participation Decision` threshold via four fixed `CircadianPeriod` time windows and transient 2–4 hour `PersonaMood` shifts, while remaining immediately responsive 24/7 with no Discord presence changes. This leaves Luna always on-call even at 3 AM, gives her no concrete daily activities to talk about, and provides no way for community members to see what she is currently doing.

## Decision

This ADR amends ADR-0015 (`Circadian Persona Drift` and `Chatter Pipeline` gating) and ADR-0019 (`Chatter Persona` lifestyle framing).

1. **Daily LLM-Generated `Daily Schedule` Persisted in SQLite (No Hardcoded Fallback)**:
   - Generate one globally shared 24-hour `Daily Schedule` per local calendar day (in `CHATTER_TIMEZONE`, default `Asia/Taipei`) at day rollover (or on startup if missing) via Gemini, grounded in `Chatter Persona` (freelance creator / flexible homebody night-owl lifestyle), day-of-week, and a brief summary of the previous day's schedule for natural cross-day continuity.
   - Persist `Daily Schedule` and its contiguous `Schedule Block` entries (`00:00–24:00`) in SQLite so restarts preserve the same day's plan.
   - **No hardcoded fallback schedule**: If LLM generation fails, log `ERROR` (notifying operators via `OwnerErrorNotifier`) and retry with exponential backoff. Until today's schedule succeeds, `Chatter Pipeline` stays un-gated rather than silently disabling chat or fabricating a static template.

2. **Four-Tier `Availability State` and Universal Deferral During `BUSY` / `SLEEPING`**:
   - Each `Schedule Block` declares an `Availability State`:
     - `AVAILABLE`: Real-time `Chatter Pipeline` participation governed by `Participation Decision` + `PersonaMood`; triggers `Deferred Catch-Up` on entry from an unavailable streak.
     - `BREAK`: Triggers `Deferred Catch-Up` on entry; allows real-time replies during the block with a slightly elevated participation threshold and concise "quick phone break" tone.
     - `BUSY` / `SLEEPING`: Defers **all** `Chatter Pipeline` replies—including direct `@Luna` mentions and replies to Luna—without invoking LLM calls, recording the channel's `Catch-Up Watermark` in SQLite when eligible non-bot messages arrive.
   - Scope isolation: `Daily Schedule` gates only `Chatter Pipeline`. `Summary Pipeline`, `SocialPreviewPlugin`, `CurtlyPlugin`, MCP, and all slash commands remain 24/7 responsive.

3. **Selective `Deferred Catch-Up` on Transition to `BREAK` / `AVAILABLE`**:
   - When transitioning from `BUSY` or `SLEEPING` into `BREAK` or `AVAILABLE`, inspect allowed channels that have pending `Catch-Up Watermark` entries, fetching bounded missed messages (capped by message count and max staleness window) and running a lightweight catch-up triage LLM pass.
   - Prioritize direct `@Luna` mentions / replies to Luna (still skippable if obsolete), and select at most `0–2` interesting ambient messages per channel. Each selected item produces either a Discord `message.reply` (with natural staggered delays and typing simulation, coordinated with `BoundedChannelBurstCoordinator` if live chat intervenes) or a `Silent Reaction Path` emoji reaction.
   - Abort remaining catch-up items if the schedule transitions back to `BUSY` or `SLEEPING` mid-batch, and advance the channel's `Catch-Up Watermark` in SQLite once reviewed.

4. **Integration with `Circadian Persona Drift` and Prompt Context**:
   - When a `Daily Schedule` is active, it replaces the four fixed `CircadianPeriod` windows while keeping the transient 2–4 hour in-memory `PersonaMood` fluctuation layered on top during `AVAILABLE` and `BREAK` blocks.
   - Inject the active `Schedule Block` (plus a compact outline of today's schedule) into `ChatterPromptBuilder` so Luna's dialogue naturally reflects what she is doing or just finished, and record schedule state in `AI Trace`.

5. **Discord Presence Mapping and Public `/schedule` Command**:
   - Synchronize Luna's global Discord presence on startup and at each `Schedule Block` boundary:
     - `AVAILABLE` / `BREAK` → `Online` + block custom status text.
     - `BUSY` → `DoNotDisturb` + block custom status text.
     - `SLEEPING` → `Idle` + block custom status text (chosen over `Invisible` because Discord hides custom status text while offline).
   - Expose a single public global slash command `/schedule` displaying today's full 00:00–24:00 timeline in a Discord Embed with the current block highlighted and status icons; omit owner-only regeneration subcommands.

## Consequences

- **Positive**: Luna exhibits believable daily rhythms, visible Discord presence, and natural selective catch-up after being away; SQLite persistence ensures restart consistency and deduplicated catch-up; zero LLM tokens are spent on `Chatter Pipeline` during `BUSY`/`SLEEPING` blocks.
- **Negative / Trade-offs**: Direct `@Luna` mentions during `BUSY` or `SLEEPING` will not receive immediate replies until the next `BREAK` or `AVAILABLE` window; a single global schedule means all guilds share one timezone and presence; transient Gemini outages at midnight leave Luna temporarily un-gated until backoff retry succeeds rather than showing a synthetic fallback schedule.
