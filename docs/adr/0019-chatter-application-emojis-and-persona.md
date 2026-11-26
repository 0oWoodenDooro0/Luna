# Chatter Application Emoji Priority and Persona Identity

Status: accepted (amended by [ADR 0021](0021-chatter-daily-schedule-and-deferred-catch-up.md), [ADR 0025](0025-chatter-user-affinity-and-selective-response.md))

## Context

Under ADR-0015 and ADR-0016, `Chatter Pipeline` only fetched, pre-scanned, ranked, and allowlisted custom emojis belonging to the target Discord guild, stripping bot-owned Discord Application Emojis from generated replies and rejecting them in `Silent Reaction Path`. Meanwhile, `ChatterPromptBuilder` defined Luna as a generic casual group member with strict zero-punctuation and plain-text-default rules, while `CircadianPersonaDrift` cues (`TIRED`, `LOW_BATTERY`, `LATE_NIGHT`) flattened her voice into a detached bystander.

## Decision

This ADR amends ADR-0015 (`Chatter Custom Emoji Policy`, `Circadian Persona Drift`, `Silent Reaction Path`, and reply formatting) and ADR-0016 (emoji pre-scan, prompt pool budgeting, and `Emoji Usage Ranking`).

1. **Bot Application Emoji Discovery, Pre-Scan, and TTL Refresh**:
   - Fetch Luna's own Discord Application Emojis via Kord `0.19.0-SNAPSHOT` (`Kord.emojis` / `rest.application.getApplicationEmojis`), reusing `ChatterEmojiCatalog`'s global `emoji_id` `Custom Emoji Meaning` cache so both dialogue transcript annotation and prompt selection pools understand Luna's own emojis.
   - Because Discord emits no Gateway event when Application Emojis are added, renamed, or deleted, load and background pre-scan them on startup and refresh an in-memory cache on a **10-minute TTL**, triggering background vision resolution for any newly discovered uncached IDs without adding a manual refresh slash command.

2. **Emoji Priority, Allowlist, and Partitioned Prompt Quotas**:
   - Enforce priority order across both paths:
     - **Text Replies**: Bot Application Emoji → Target Guild Custom Emoji → Plain text (retain `EmojiSanitizer` stripping of standard Unicode emojis; expand `allowedCustomEmojiIds` to `applicationEmojiIds ∪ guildEmojiIds`).
     - **`Silent Reaction Path`**: Bot Application Emoji → Target Guild Custom Emoji → Standard Unicode Emoji → `NONE` (expand `ChatterReactionEmojiValidator` to accept `applicationEmojis ∪ guildEmojis` alongside single Unicode emojis).
   - Partition the prompt selection pool into two explicit sections—Luna's own emojis (preferred) and guild emojis (fallback only when no application emoji fits)—with independent caps so a large application emoji library cannot crowd out guild emojis:
     - **Text Replies**: up to `15` application emojis + `10` guild emojis.
     - **`Silent Reaction Path`**: up to `30` application emojis + `20` guild emojis.
   - Rank application emojis over the cap using the existing 30-day rolling `Emoji Usage Ranking` table via a fixed global sentinel key (`__app__`), counting both Luna's own dispatches and member occurrences across guilds, with deterministic name/ID backfill.
   - Relax text reply emoji guidance slightly so Luna naturally pairs at most `1` fitting emoji (preferring her own) when expressing clear emotion (smugness, quick apology, mild frustration, teasing), while keeping neutral chat plain text.

3. **`Chatter Persona`, Expressive Punctuation, and Aligned `Circadian Persona Drift`**:
   - Hardcode Luna's `Chatter Persona` directly in Kotlin (`ChatterPromptBuilder`), distilled purely as situational behavioral traits without verbatim colloquial dialogue examples: a quick-witted, curious, sharp-tongued yet approachable girl who is confident and slightly mischievous yet occasionally scatterbrained, owns up immediately without excuses when caught wrong, gets mildly thin-skinned/flustered when pressed yet cools down quickly when placated, and helps friends naturally rather than acting like a service assistant. Bound teasing and mock-reluctance strictly to friendly banter (never personal attacks or genuinely refusing serious help).
   - Retain ultra-concise 2–3 short `\n`-separated lines and avoidance of commas/periods (`，`, `。`), while relaxing expressive punctuation (`？`, `！`) so reactions and teasing read naturally.
   - Keep `Circadian Persona Drift` time-of-day windows and `Participation Decision` threshold offsets, rewriting `CircadianPeriod` and `PersonaMood` `promptCue` strings so energy/time shifts express variations of `Chatter Persona` rather than overriding it.
   - Apply persona awareness across Reply Generation, `Silent Reaction Path`, and `Participation Decision` (lightly favoring curious, friendly engagement), keeping `Summary Pipeline` completely isolated.

## Consequences

- **Positive**: Luna can express herself consistently across every guild using her own visually understood Application Emojis without starving guild-specific memes; her replies, silent reactions, and participation decisions share a cohesive, recognizable personality that stays natural under circadian shifts.
- **Negative / Trade-offs**: Newly uploaded or deleted Application Emojis take up to 10 minutes to reflect without a restart; partitioned emoji pools slightly increase text-reply prompt size (up to 25 total emoji entries vs 15 previously); hardcoded persona wording requires a code change to tune rather than an environment variable.
