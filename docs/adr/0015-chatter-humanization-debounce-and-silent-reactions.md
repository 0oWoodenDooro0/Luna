# Humanized Chatter Pipeline: Burst Debounce, Overlapping Reading, Persona Drift, and Silent Reactions

Status: accepted (amended by [ADR 0027](0027-chatter-conversation-momentum.md), [ADR 0029](0029-chatter-ambient-fatigue-and-anti-hijack-gating.md))

## Context

`Chatter Pipeline` previously processed Discord `MessageCreateEvent`s immediately upon receipt, firing `channel.type()` prior to `ChatterGenerator` execution, dropping concurrent ambient messages via `BoundedChannelInFlightTracker`, sending monolithic unreferenced `channel.createMessage` payloads, stripping all emojis uniformly, and rejecting sticker-only or Tenor/Giphy GIF-only messages as blank noise. This produced robotic timing (premature replies to multi-line message bursts, phantom typing indicators when generation returned blank or failed), zero awareness of local time or fluctuating conversational energy, and inability to react silently to memes or stickers.

## Decision

1. **Message-Only `Burst Debounce Window` with Full Pre-Send Cancellation & Starvation Cap**:
   - Trigger and cancel per-channel jobs strictly on `MessageCreateEvent` rather than subscribing to `TypingStartEvent` (avoiding `Intent.GuildMessageTyping` gateway noise and spurious cancellations while members are merely typing).
   - Keep `CHATTER_COOLDOWN_SECONDS` pre-filtering in `ChatterNoiseFilter` for ambient messages while bypassing cooldown for direct `@Luna` mentions.
   - Coalesce rapid bursts over a `2.5s` base debounce (`1.0s–1.5s` for direct `@Luna`) capped at `7s` (`6s–8s` configurable ceiling) from the initial trigger of the round.
   - Allow newer `MessageCreateEvent`s arriving during reading, `Participation Decision`, reply generation, or pre-send typing playback to `cancel()` the in-flight job and restart (recording cancelled runs as `SKIPPED` in `AI Trace`), capped at `2` consecutive restarts per round before locking in delivery to prevent starvation in busy channels.
   - Isolate active direct `@Luna` jobs so only follow-up messages from the same triggering author merge/restart them, preventing unrelated bystander chatter from cancelling a direct mention response.

2. **Concurrently Scheduled `Overlapping Reading Simulation` & Length-Paced Typing**:
   - Once the `Burst Debounce Window` closes, run silent reading simulation (`0.8s–2.0s` random delay without calling `channel.type()`) concurrently with `Participation Decision` and `ChatterGenerator` so AI network latency overlaps with human reading time.
   - Trigger `channel.type()` only after both reading simulation and reply generation complete, delaying `min(charCount * 120ms, 4000ms)` before dispatching the opening message.

3. **`Asymmetric Reply Threading` & `Segmented Burst Delivery`**:
   - Before sending segment 1, count non-Luna messages posted after the target message: use `channel.createMessage` for `0–1` intervening messages, and `message.reply` on segment 1 only when `>= 2` non-Luna messages have intervened.
   - Instruct the prompt to separate natural thoughts with single `\n` breaks and split into at most `3` segments (merging any remainder into segment 3). Send segment 1 after the initial typing delay, then trigger `channel.type()` and pause `0.7s–1.5s` before each subsequent `createMessage` segment; once segmented delivery begins, finish all segments in the batch.

4. **`Circadian Persona Drift` (In-Memory Only)**:
   - Inject local day-of-week, time, and four time-of-day persona cues (`01:00–05:59`, `06:00–11:59`, `12:00–17:59`, `18:00–00:59`) into `ChatterPromptBuilder` using `CHATTER_TIMEZONE` (default `Asia/Taipei`) and an injectable `Clock`.
   - Maintain a transient in-memory mood/energy state shifting randomly every `2–4` hours without database persistence, adjusting system prompt tone and shifting the effective `Participation Decision` threshold (`-0.08` to `+0.12`, plus `+0.08` late-night offset) around `CHATTER_DECISION_THRESHOLD`, recording active state and effective threshold in `AI Trace`.

5. **`Silent Reaction Path` (No Local Fallback Pool) & `Guild Custom Emoji Policy`**:
   - When `Participation Decision` returns an intermediate `noul` score in `[reactionThreshold, effectiveThreshold)`, skip full reply generation and invoke a lightweight multimodal LLM call (`maxOutputTokens = 16`) to pick a single reaction emoji (`message.addReaction`, permitting standard Unicode emojis and guild custom emojis). If the selection call fails or returns an invalid emoji, fail closed without a local fallback pool and record `SKIPPED` in `AI Trace`.
   - Extend `ChatterNoiseFilter` and `ChatterContextCollector` / `ChatImageAttachmentExtractor` to admit sticker-only and GIF-embed messages: pass raster Discord stickers (`PNG`/`APNG`/`GIF`) and Tenor/Giphy embed preview images via `CDN Direct Ingestion`, and degrade `LOTTIE` stickers to `[貼圖: <name>]`.
   - Keep `EmojiSanitizer` stripping Unicode emojis from generated text replies while allowing verified custom emojis (`<:name:id>` / `<a:name:id>`) present in the target Discord guild's emoji list, stripping any hallucinated IDs.
   - Explicitly exclude probabilistic post-send typo self-correction (`edit`) from this iteration.

## Consequences

- **Positive**: Eliminates premature replies to multi-line bursts and phantom typing indicators on skipped/cancelled runs; preserves zero-token-waste pre-debounce while hiding AI latency behind concurrent reading simulation; enables low-energy silent reactions and full meme/sticker/GIF comprehension.
- **Negative / Trade-offs**: Pre-send `MessageCreateEvent` cancellation of an in-flight Gemini call consumes tokens for the aborted attempt (mitigated by the `2.5s–7s` pure debounce phase and `2`-restart starvation cap); `Silent Reaction Path` skips reacting entirely if the lightweight selection call fails rather than falling back to generic hardcoded emojis.
