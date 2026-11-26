# Invisible Sleep Presence and Native Discord Activity Types

Status: accepted

## Context

Under ADR-0021, `KordSchedulePresencePort` maps `AvailabilityState.SLEEPING` to `PresenceStatus.Idle` and renders every `Schedule Block` as a plain Discord Custom Status (`state = block.statusText`). In practice, staying permanently on yellow `Idle` overnight feels less natural than appearing offline while asleep, and real Discord profiles dynamically show native activity prefixes (`Playing ...`, `Listening to ...`, `Watching ...`) depending on the current activity. Meanwhile, `/schedule` already exposes Luna's full 24-hour timeline and sleep status text on demand.

## Decision

This ADR amends ADR-0021 (Section 5: Discord Presence Mapping) and extends the `Schedule Block` / `Schedule Commitment` schema from ADR-0023.

1. **`SLEEPING` Maps Unconditionally to `PresenceStatus.Invisible`**:
   - `KordSchedulePresencePort` maps `AvailabilityState.SLEEPING` strictly to `PresenceStatus.Invisible` instead of `PresenceStatus.Idle` (rather than randomizing between `Invisible` and `Idle`).
   - When syncing an `Invisible` block, `kord.editPresence` sets `status = PresenceStatus.Invisible` without attaching a custom status or native activity, since Discord hides presence activities while offline. Gateway event reception and slash commands (including `/schedule`) remain unaffected.
   - `AVAILABLE` / `BREAK` (`Online`) and `BUSY` (`DoNotDisturb`) status mappings remain unchanged.

2. **Four-Value `Presence Activity Type` Reusing Single `statusText` Field**:
   - Add an optional `activityType` field (default `CUSTOM`) supporting four values: `CUSTOM`, `PLAYING`, `LISTENING`, and `WATCHING` (omitting `COMPETING` to fit Luna's persona).
   - Reuse the existing `statusText` field rather than adding a separate `activityName` column. Prompts constrain `statusText` by type:
     - `PLAYING` / `LISTENING` / `WATCHING`: concise object/noun phrase only, without redundant verbs or emojis (so Discord renders `Playing 薩爾達傳說`, `Listening to Lo-Fi 歌單`, `Watching 深夜新番`).
     - `CUSTOM`: natural conversational status phrase with optional emoji.
   - Because Kord `0.19.0-SNAPSHOT` `PresenceBuilder` sends a single `DiscordBotActivity` and attaches `state` onto a native activity when both are set, `KordSchedulePresencePort` calls only `playing(statusText)` / `listening(statusText)` / `watching(statusText)` for native types (without setting `state`) and sets only `state = statusText` for `CUSTOM`.

3. **Full-Path Propagation and Backward-Compatible Persistence**:
   - Propagate optional `activityType` (default `CUSTOM`) across all three block-creation paths: `DailyScheduleGenerator`, `ChatterScheduleToolHandler` (`update_today_schedule` and `manage_schedule_commitment`), and `ScheduleCommitment` / `DailyScheduleSplicer`.
   - Unknown or omitted `activityType` values from LLM outputs or legacy `blocks_json` rows gracefully fall back to `CUSTOM` rather than failing schedule parsing.
   - `SqliteDailyScheduleStore` adds `activity_type TEXT NOT NULL DEFAULT 'CUSTOM'` to `chatter_schedule_commitments` via an idempotent schema check (`PRAGMA table_info` + `ALTER TABLE`).

## Consequences

- **Positive**: Luna appears genuinely offline during `SLEEPING` blocks while `/schedule` continues to show her sleep status; Discord profiles display native `Playing` / `Listening to` / `Watching` presence across both daily schedules and conversationally adjusted/committed plans; existing SQLite rows deserialize without manual migration.
- **Negative / Trade-offs**: Discord no longer displays Luna's sleep `statusText` in the member sidebar while `SLEEPING` (visible via `/schedule` instead); prompt adherence is required so LLM-generated native `statusText` values avoid redundant prefixes or emojis.
