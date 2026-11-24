# Agentic Schedule Adjustment and Cross-Day Schedule Commitments

Status: accepted

## Context

Under ADR-0021, Luna's `Daily Schedule` is generated once per local calendar day at midnight and remains immutable until the next day rollover. While this gives Luna believable daily routines and Discord presence, she cannot adapt her plans to live group conversations—such as joining members to play a game right now, shifting an afternoon task, agreeing to go to bed early, or locking in a hangout for tomorrow evening.

## Decision

This ADR amends ADR-0021 (`Daily Schedule`, `Deferred Catch-Up`, and `/schedule` presentation).

1. **Dual-Tool `Agentic Schedule Adjustment` Surface**:
   - Register two dedicated function tools in `Chatter Pipeline` (sharing the existing tool-calling loop, turn/timeout limits, and `AI Trace` recording with `Agentic Memory Retrieval`):
     - **Today's remaining timeline tool**: Accepts one or more structured time-range patches (`start_time`, `end_time`, `title`, `detail`, `status_text`, `availability`) and deterministically splices/trims them into today's contiguous `00:00–24:00` `Daily Schedule` in Kotlin. This guarantees gap-free, overlap-free invariants and sub-10ms execution inside the existing tool timeout, avoiding full-day LLM rewrites or secondary LLM sub-calls while still allowing adjacent blocks to be shifted in one call.
     - **Future `Schedule Commitment` tool**: Creates, updates, or cancels sparse cross-day plans for upcoming local calendar dates within a **7-day horizon** (tomorrow through `+7` days).
   - Expose both tools during **both live `Chatter Pipeline` replies and `Deferred Catch-Up` replies**, strictly enforcing a **future-only boundary (`>= now` in `CHATTER_TIMEZONE`)**: past blocks are immutable; a today-patch whose `start_time` is slightly before `now` is clamped to `now`, and any patch wholly in the past is rejected with a clear message to the model.

2. **Cross-Day `Schedule Commitment` Persistence and Rollover Anchoring**:
   - Persist `Schedule Commitment` entries globally in SQLite (consistent with ADR-0021's single global schedule across guilds), capped at **10 active (non-expired) commitments** at any time.
   - Inject active upcoming commitments (next 7 days) as a compact list into `ChatterPromptBuilder` so Luna remembers her booked plans during conversation and can reschedule or cancel them naturally.
   - At day rollover (or startup generation for a missing day), pass that date's `Schedule Commitment` entries into the schedule-generation prompt so the LLM builds natural surrounding blocks, then **deterministically splice** those commitments onto the generated `Daily Schedule` post-generation so booked times are never dropped or drifted by the LLM.
   - Because `getPreviousDaySummary` reads the persisted end-of-day schedule from SQLite, conversation-adjusted activities naturally carry into next-day continuity.

3. **All Four `Availability State` Values Permitted with Guardrails**:
   - Conversational edits may set any `Availability State` (`AVAILABLE`, `BREAK`, `BUSY`, `SLEEPING`), allowing both "let's hang out / game together" (`AVAILABLE` / `BREAK`) and "okay I'll go sleep / lock in now" (`SLEEPING` / `BUSY`).
   - Protect against self-silencing abuse and edit thrashing with layered guardrails:
     - **Persona prompt guidance**: Instruct Luna to evaluate invitations and nudges in character (`Chatter Persona`) and push back on obvious trolling or absurd requests.
     - **Hard duration caps**: Per-edit maximum of **4 hours** for `BUSY` and **8 hours** for `SLEEPING` (clamped at `24:00` for today's schedule).
     - **Global sliding-window rate limits**: Maximum **3 schedule mutations per 10 minutes** and **10 mutations per local calendar day** globally, allowing quick back-and-forth rescheduling in chat without hitting a rigid per-edit cooldown while blocking spam.
   - Guardrail rejections return an explicit, model-readable reason so Luna can reply naturally in character rather than failing silently.

4. **Immediate Propagation, Presence Sync, and `/schedule` Visibility**:
   - Successful today-schedule edits immediately persist to SQLite, update `DailyScheduleCoordinator`'s in-memory `activeSchedule`, wake/reset `runLoop()` to recalculate the next block boundary timer, and **immediately sync Discord Presence** when the current block changes.
   - If an edit during `Deferred Catch-Up` switches the current block to `BUSY` or `SLEEPING`, ADR-0021's existing transition check aborts remaining queued catch-up items while allowing the current reply to finish sending.
   - Mark conversation-adjusted `Schedule Block` entries (persisted in `blocks_json`) with a lightweight visual indicator in `/schedule` (e.g., `✏️` / `(臨時調整)`), and append a compact upcoming-commitments section to `/schedule` whenever future `Schedule Commitment` entries exist.

## Consequences

- **Positive**: Luna's daily timeline and Discord presence react organically to group conversations in real time; cross-day invitations carry forward into future generated schedules without drifting; deterministic range splicing preserves `00:00–24:00` invariants with negligible latency; sliding-window quotas and duration caps prevent trolling without hindering normal negotiation.
- **Negative / Trade-offs**: Because `Daily Schedule` and `Schedule Commitment` remain globally shared across guilds, an adjustment agreed to in one guild affects Luna's availability and presence in all guilds; allowing `BUSY`/`SLEEPING` edits means a convincing conversation can temporarily pause real-time replies across guilds until the block ends (bounded by the 4h/8h caps).
