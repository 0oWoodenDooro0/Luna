# Chatter Per-User Affinity, Tone Adaptation, and Selective Response

Status: accepted

## Context

Under [ADR 0019](0019-chatter-application-emojis-and-persona.md) and [ADR 0022](0022-chatter-memory-ranking-and-social-understanding.md), `Chatter Pipeline` stores qualitative `Social Understanding` impressions in `chatter_understandings` and pre-injects them into `ChatterGenerator`, while keeping `Participation Decision` completely agnostic to who is speaking and bypassing `Participation Decision` unconditionally for direct mentions (`ChatterService.kt:281`). Furthermore, ADR-0019 binds `Chatter Persona` to uniformly friendly banter that never refuses serious help. As a result, Luna treats every guild member with largely uniform warmth and responsiveness regardless of recurring positive rapport or repeated friction.

> _Amends [ADR 0019](0019-chatter-application-emojis-and-persona.md) Section 3 (`Chatter Persona` refusal boundary) and [ADR 0022](0022-chatter-memory-ranking-and-social-understanding.md) Sections 1, 3, 4, and 5 (`Understanding Pre-Injection`, Stage 3 `Memory Distillation`, and `/memory` visibility/clear semantics)._

## Decision

1. **Hybrid Per-User Relationship Model (`chatter_relationships` + `Social Understanding`)**:
   - Introduce a guild-isolated pure-relational SQLite table `chatter_relationships` keyed by `(guild_id, user_id)` (zero `vec0` / `FTS5` overhead, fail-open to `NEUTRAL` on lookup failure) storing:
     - `affinity_score` (`INTEGER NOT NULL DEFAULT 0`, clamped to `-100..+100`) mapped deterministically to a five-level `Affinity Tier`: `DISLIKED`, `COOL`, `NEUTRAL`, `WARM`, `FAVORITE`.
     - A concise subjective feeling and interaction tone cue (e.g., `tone_style` / `feeling_summary`) capturing how Luna personally feels about and talks with that user beyond raw warmth (e.g., playful banter partner vs. gentle/polite tone vs. curt indifference).
     - `created_at`, `updated_at`, and `last_interaction_at` timestamps.
   - Keep `chatter_understandings` (`Social Understanding`) as the qualitative memory of recurring facts/traits and shared history, while `chatter_relationships` supplies the stable quantitative stance and per-user tone cue. Keep `Summary Pipeline` completely isolated.

2. **Zero-Extra-Call Updates in `Memory Distillation` Stage 3 & Asymmetric `Negative Affinity Decay`**:
   - Extend Stage 3 (`UnderstandingPatchPrompt`, `gemini-3.5-flash-lite`, fail-open) to emit bounded per-user relationship updates (`affinityDelta` clamped per batch, e.g., `-15..+15`, plus optional `tone_style` / `feeling_summary` refinement) alongside `Social Understanding` patches, adding zero extra LLM calls or reply latency. Within an active conversation, immediate reactions remain naturally driven by the `Working Memory Window`.
   - Apply asymmetric `Negative Affinity Decay`: after an inactivity cooldown (e.g., 7 days without interaction), negative `Affinity Score` values drift slowly back toward `0` (`NEUTRAL`) over time so strained relationships do not permanently deadlock, while positive scores do not decay automatically and remain durable unless lowered by subsequent friction. Positive interactions during active dialogue continue to restore affinity normally via positive deltas.

3. **Two-Layer Tone Adaptation & Amended Persona Refusal Boundary (Amends ADR-0019)**:
   - Extend `Understanding Pre-Injection` in `ChatterGenerator` / `ChatterPromptBuilder` to include each active speaker's `Affinity Tier` (baseline warmth/distance) and subjective feeling / tone cue alongside their `Social Understanding` entries, while constraining all per-user tone variations to stay coherent with Luna's core `Chatter Persona`.
   - Amend ADR-0019 Section 3: retain the hard safety floor prohibiting personal attacks, verbal abuse, and instigating real guild conflict, while relaxing the unconditional helpfulness requirement so Luna may adopt bounded aloofness/curtness toward `COOL` or `DISLIKED` users and may explicitly express unwillingness to answer or refuse requests when she dislikes someone.

4. **Selective Response Routing Across All Three Chatter Entry Paths**:
   - **Ambient Chat (`Participation Decision`)**: Layer the triggering author's deterministic `affinityThresholdOffset` (e.g., `FAVORITE: -0.10` through `DISLIKED: +0.22`, clamped to a safe threshold range) onto `effectiveThreshold` alongside `Circadian Persona Drift`—keeping `GeminiDecisionClient` prompt text free of memory injection and adding zero extra token/latency cost. Suppress automatic friendly `Silent Reaction Path` triggers when the triggering author is `DISLIKED`.
   - **Direct Mentions (`Selective Mention Disengagement`)**: Keep `NEUTRAL`, `WARM`, and `FAVORITE` direct mentions on the existing zero-decision-latency reply path (`ChatterService.kt:281`). For `COOL` and `DISLIKED` direct mentions, still route into `ChatterGenerator` so the model inspects full conversation context (distinguishing provocation from sincere apology or important messages), while explicitly permitting it to return either a curt/aloof reply, an explicit refusal, or a structured disengagement signal that triggers silent non-response or a cold `Silent Reaction Path`.
   - **`Deferred Catch-Up`**: Inject each missed speaker's `Affinity Tier` into `DeferredCatchUpTriageClient` so backlog triage naturally favors liked users and more readily skips disliked users.

5. **Hidden Affinity in `/memory`, Reset on `/memory clear`, and `AI Trace` Observability**:
   - Keep `Affinity Score` and `Affinity Tier` hidden from `/memory list` and `/memory delete` to preserve persona immersion, prevent score-gaming, and avoid social friction in guild channels.
   - Update `/memory clear confirm:true` to also delete/reset the invoking user's `chatter_relationships` row in the current guild back to neutral alongside `chatter_memories` and `chatter_understandings`.
   - Record triggering-author `relationshipTier`, `affinityScore`, `affinityThresholdOffset`, and low-affinity disengagement outcomes in `AiTraceRecord` for operator observability and threshold tuning.

## Consequences

- **Positive**: Luna develops distinct per-user warmth, conversational styles, and realistic reply selectivity across ambient chat, direct mentions, and deferred catch-up at zero extra embedding or decision-API latency; asymmetric `Negative Affinity Decay` prevents permanent cold-war lockouts while keeping friendships durable; hiding raw scores from `/memory` preserves natural roleplay while `/memory clear` still honors full user data reset.
- **Negative / Trade-offs**: Low-affinity direct mentions still incur one `ChatterGenerator` call to judge context before optionally disengaging; `UnderstandingPatchPrompt` schema becomes slightly richer and must remain fail-open; relaxing ADR-0019's refusal boundary requires careful prompt wording and evaluation so aloof refusals stay clearly within bounded persona tone rather than crossing into hostility.
