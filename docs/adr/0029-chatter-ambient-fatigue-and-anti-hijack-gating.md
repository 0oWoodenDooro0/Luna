# Chatter Ambient Fatigue, Direct-Only Momentum, and Anti-Hijack Gating

Status: accepted

> _Amends [ADR 0027](0027-chatter-conversation-momentum.md) (Section 3: `Conversation Momentum` activation boundary and default `CHATTER_MOMENTUM_MAX_TURNS`) and [ADR 0015](0015-chatter-humanization-debounce-and-silent-reactions.md) (default `CHATTER_COOLDOWN_SECONDS` and `CHATTER_REACTION_THRESHOLD`)._

## Context

Production `AI Trace` logs (`data/logs/ai/ai-*.jsonl`) showed Luna chiming into ongoing two-person channel conversations far too frequently (42% of consecutive text replies in the same channel occurred within 60 seconds, and 53% within 3 minutes). Root-cause analysis revealed a strong self-reinforcing loop:

1. **Self-reinforcing `Participation Decision` scores**: When Luna appeared in the last 5 lines of `[Recent Context]`, `AMBIENT` evaluations with `noul >= 0.80` jumped from **11.6%** (median `0.331`) to **44.8%** (median `0.754`). Because `Segmented Burst Delivery` sends 2–3 consecutive Discord messages per reply, Luna's own lines dominated `[Recent Context]`, causing `gemini-decision-1` to treat subsequent human-to-human exchanges as follow-ups to Luna.
2. **Ambient-triggered `Conversation Momentum`**: Under [ADR 0027](0027-chatter-conversation-momentum.md), uninvited `AMBIENT` replies also activated `Conversation Momentum` (`1.5s` cooldown floor and `-0.10` threshold boost), actively accelerating consecutive ambient interjections instead of dampening them.
3. **Permissive defaults and decision prompt**: Default `CHATTER_DECISION_THRESHOLD = 0.70` and `CHATTER_COOLDOWN_SECONDS = 5` combined with `Circadian Persona Drift` (`-0.10`) and momentum (`-0.10`) pushed `effectiveThreshold` down to `0.52–0.62`, while `GeminiDecisionClient.DEFAULT_INSTRUCTIONS` lacked an explicit post-reply convergence ("anti-hijack") rule.

## Decision

1. **Restrict `Conversation Momentum` to `Direct Interaction` Only**:
   - Only delivered replies to a `Direct Interaction` (`@Luna` mention or ping-off reply to Luna per [ADR 0028](0028-chatter-ping-off-reply-direct-interaction.md)) start or refresh `Conversation Momentum` (`ambientTurnCount = 0`).
   - Uninvited `AMBIENT` replies (when momentum is inactive) never start or refresh `Conversation Momentum`.
   - When an `AMBIENT` reply is delivered while `Direct Interaction`-initiated momentum is active, it increments `ambientTurnCount` toward `CHATTER_MOMENTUM_MAX_TURNS` (default lowered from `3` to `2`) without accumulating `Ambient Fatigue`.

2. **Introduce Per-Channel In-Memory `Ambient Fatigue` (Bounded LRU)**:
   - Track uninvited `AMBIENT` text replies per channel in a bounded in-memory LRU cache (matching `CODING_STANDARDS.md` §1, zero database persistence, injectable `timeSource`).
   - Within a sliding window (`CHATTER_FATIGUE_WINDOW_SECONDS`, default `300`s, `0` disables), each uninvited `AMBIENT` text reply increments the channel's fatigue count and adds a linear threshold penalty (`count * CHATTER_FATIGUE_STEP`, default `0.08`, capped at `CHATTER_FATIGUE_MAX_OFFSET`, default `0.24`), applied alongside `Circadian Persona Drift` and `Affinity Tier` before clamping `effectiveThreshold` to `[0.15, 0.95]`.
   - **Synchronized `Silent Reaction Path` damping**: Add the same active fatigue offset to the evaluated `reactionThreshold` (clamped so it does not exceed `effectiveThreshold`) so Luna steps back consistently across both text and reactions. Silent reactions themselves—and `Deferred Catch-Up` deliveries—never accumulate `Ambient Fatigue`.
   - **Reset semantics**: `Ambient Fatigue` resets to `0` after `CHATTER_FATIGUE_WINDOW_SECONDS` of uninvited silence in the channel, or immediately upon a delivered `Direct Interaction` reply (inviting Luna back into the conversation).

3. **Coalesce Consecutive Same-Author Turns in Decision Context**:
   - In `ChatterContextCollector.formatDecisionTranscript`, coalesce consecutive messages by the same author within `[Recent Context]` into a single multi-line speaker block—without crossing time-gap markers or Discord reply-reference annotations—so Luna's 2–3 segmented messages (and rapid human multi-line bursts) count as one conversational turn rather than dominating `[Recent Context]`.
   - Keep `[Latest Message]` isolated as the single triggering turn and leave `ChatterGenerator`'s full reply history formatting unchanged.

4. **Tighten `Participation Decision` Default Instructions (Selective Banter & Anti-Hijack)**:
   - Update `GeminiDecisionClient.DEFAULT_INSTRUCTIONS` to tighten `Answer true when` toward channel-wide open questions, opinion requests, notable shared links/media, or direct responses to Luna, and add an explicit `Answer false when` anti-hijack clause: if Luna recently spoke in `[Recent Context]` but subsequent messages are other members talking to each other without addressing or following up on Luna, answer `false`.

5. **Conservative Defaults & `AI Trace` Observability**:
   - Raise code defaults to `CHATTER_DECISION_THRESHOLD = 0.80` (from `0.70`), `CHATTER_COOLDOWN_SECONDS = 30` (from `5`), `CHATTER_REACTION_THRESHOLD = 0.55` (from `0.45`), and `CHATTER_MOMENTUM_MAX_TURNS = 2` (from `3`).
   - Expose `CHATTER_FATIGUE_WINDOW_SECONDS`, `CHATTER_FATIGUE_STEP`, and `CHATTER_FATIGUE_MAX_OFFSET` via strict `EnvReader` configuration.
   - Record nullable `ambientFatigueCount` and `ambientFatigueOffset` on `AiTraceRecord` (default `null` for backward compatibility) and document them in [`docs/architecture/ai-tracing.md`](../architecture/ai-tracing.md).

## Considered Options

- **Hard ambient rate cap (e.g., max 2 ambient replies per 10 minutes, skipping Decision API)**: Rejected because it feels robotic when a genuinely high-signal topic or direct follow-up appears; progressive threshold fatigue (`0.80 -> 0.88 -> 0.95`) naturally silences routine chatter while still allowing exceptional triggers.
- **Removing `Conversation Momentum` entirely**: Rejected because invited back-and-forth exchanges immediately after `@Luna` or a direct reply still benefit from brief ping-free continuity; restricting momentum to `Direct Interaction` preserves the benefit without fueling ambient loops.
- **Coalescing only Luna's messages in `[Recent Context]`**: Rejected in favor of coalescing all consecutive same-author turns (respecting time-gap and reply boundaries), which treats all speakers symmetrically and prevents fragmented human bursts from skewing turn balance.

## Consequences

- **Positive**: Breaks the ambient self-reinforcing loop at all four layers (decision context weight, prompt semantics, momentum activation boundary, and progressive fatigue damping) with zero extra LLM calls or database I/O, while keeping `Direct Interaction` responsiveness intact.
- **Negative / Trade-offs**: In quiet channels where users expect Luna to react to every casual remark without `@mentioning` her, she will chime in less often unless operators lower `CHATTER_DECISION_THRESHOLD` or `CHATTER_FATIGUE_STEP` in `.env`.
