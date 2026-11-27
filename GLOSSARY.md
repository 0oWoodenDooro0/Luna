# Luna

Discord bot providing URL shortening, social preview parsing, and reaction-triggered AI content summarization.

## Language

**Summary Reaction**:
A specific emoji reaction added to a Discord message that triggers the AI summarization workflow.
_Avoid_: Trigger emoji, reaction button, bot reaction

**Summary Pipeline**:
The multi-stage content extraction and AI summarization workflow that transforms Discord messages, arbitrary web URLs, or social media links into concise text summaries.
_Avoid_: Scraping flow, summarizer runner, summarization flow

**Generic Web Resolver**:
A resolver in `:libs:socialpeek` that extracts title, main textual content, and metadata from arbitrary non-social websites. Retired from Luna's AI pipelines (`Summary Pipeline` and `Chatter Pipeline`), where `SocialPeekFactory` uses `includeDefaultResolvers(false)` and omits `GenericWebResolver` so general public web URLs are routed to Gemini's `url_context` tool instead (while `:libs:socialpeek` and `SocialPreviewPlugin` retain it as a library capability).
_Avoid_: Web scraper, raw HTML extractor, crawler

**Gemini Summarizer**:
The AI client service that formats prompts and invokes the Google Gemini API to produce structured, emoji-free text summaries.
_Avoid_: LLM agent, chatbot, AI wrapper

**Quoted Post**:
A social media post that embeds and references another underlying post, with or without author commentary.
_Avoid_: Quote-tweet, retweet with comment, nested tweet

**Conversation Slice**:
The bounded chronological sequence of messages leading up to a target message, collected to provide conversational context for a Summary Reaction.
_Avoid_: Chat chunk, message window, backscroll buffer

**Conversation Slice Collector**:
The internal engine responsible for Dual-Boundary Backtracking, Dialogue Noise Filtering, Chat Image Attachment extraction, and transcript formatting to produce an assembled context for the Summary Pipeline.
_Avoid_: Context builder, history aggregator, message preprocessor

**Dual-Boundary Backtracking**:
The history traversal algorithm that walks backward from the reaction target message up to a configurable upper bound (default 50 messages, configurable via `SUMMARY_MAX_HISTORY_MESSAGES`), halting if an inactivity gap (default 15 minutes, configurable via `SUMMARY_INACTIVITY_GAP_MINUTES`), accumulated character budget, or a previous bot summary is encountered.
_Avoid_: Reverse history search, boundary walk, message scraper

**Dialogue Noise Filter**:
The preprocessing stage that purges slash commands, custom emojis, and system events from a conversation slice—and ambient third-party bots in `Summary Pipeline`—while preserving short human conversational turns and media attachments (whereas `Chatter Pipeline` retains third-party bot messages as visible context).
_Avoid_: Sanitizer, text cleaner, regex scrubber

**Adaptive Video Processing**:
The strategy of automatically selecting between Gemini's static processing mode (for short videos <= 5m) and agentic processing mode (for long videos > 5m) to balance time-to-first-token against token budget.
_Avoid_: Dynamic mode switcher, video mode decider

**Agentic Video Understanding**:
Gemini's dynamic video comprehension mode where the model independently navigates the timeline and inspects audio, visual frames, or transcripts on demand via internal steps, cutting long-video token consumption by up to 88%.
_Avoid_: Agent loop, autonomous video watcher

**Audience Feedback Injection**:
The independent extraction of YouTube top-level comments and viewer reactions via the YouTube Data API to supplement video multimodal understanding in the summary prompt.
_Avoid_: Comment scraping, comment crawling

**Video Focus Prompting**:
The specialized prompt strategy directing the multimodal model to prioritize extracting concrete audiovisual evidence, demonstrations, and narrative milestones from the video stream while strictly isolating community comments to audience reception.
_Avoid_: Video prompt tweak, YouTube prompt tuning

**Creator Description Ingestion**:
The extraction and sanitization of video description metadata (including author-provided outlines, summaries, and timestamp markers) to serve as semantic prior context in the summary pipeline.
_Avoid_: Description scraping, info grabbing

**Luna Host (Luna Core)**:
The central runtime container and infrastructure orchestrator responsible for configuration loading, database connection pooling, Ktor server bootstrapping, Kord bot lifecycle, and plugin discovery.
_Avoid_: Main app, bot core, runner

**Luna Plugin**:
An isolated, self-contained business module implementing the plugin lifecycle, encapsulating Discord commands, event listeners, and optional Ktor Web routes without directly depending on peer plugins.
_Avoid_: Bot addon, extension script, sub-feature

**Plugin Context**:
The unified runtime boundary injected by Luna Host into each plugin during initialization, providing managed access to Kord, Ktor Routing, the database, and type-safe plugin configurations.
_Avoid_: Global state, service locator, environment container

**Shared Library**:
A stateless, framework-independent utility library residing in `libs/` that is shared across two or more plugins or decoupled services (such as SocialPeek and Config) or targeted for standalone external distribution.
_Avoid_: Helper module, common plugin, shared code

**Chat Image Attachment**:
An image file (`image/jpeg`, `image/png`, `image/webp`, `image/gif`) attached to a Discord message within a Conversation Slice, ingested as visual context for the Summary Pipeline.
_Avoid_: Picture upload, chat photo, message media

**CDN Direct Ingestion**:
The multimodal delivery strategy of passing Discord's signed CDN attachment URL directly to the Gemini Interactions API (`/v1beta/interactions`) via `type: "image"` input blocks (`uri` and `mime_type`), and low-resolution `type: "image"` blocks in `/v1beta/decisions`, instead of legacy `:generateContent` `file_data.file_uri`, avoiding server-side binary proxying.
_Avoid_: Direct URL passing, Discord CDN proxy, raw URL injection

**Webhook Replacement**:
A Discord message dispatched by Luna via a managed channel webhook masquerading as the original author to display clean tracking-free URLs and rich social media previews after deleting the original message.
_Avoid_: Masquerade message, proxy message, fake user post

**Decoupled MCP Server**:
The standalone service exposing the Model Context Protocol over HTTP Server-Sent Events to external AI agents (such as Google Gemini Apps) while maintaining zero compile-time or runtime dependencies on the Discord bot daemon or Discord Gateway.
_Avoid_: Discord MCP bot, embedded bot MCP server

**Channel Webhook Broadcast**:
The asynchronous multi-channel fan-out mechanism where incoming agent messages are dispatched across all registered Discord incoming webhook endpoints.
_Avoid_: Channel spammer, multi-bot poster

**Embedded OAuth Provider**:
The internal OAuth 2.1 authorization server integrated within the MCP service that advertises RFC 8414 metadata discovery and validates credentials without requiring a third-party identity service.
_Avoid_: External auth server, Auth0 integration

**Shared MCP Data Store**:
The cross-process SQLite storage library residing in `libs/mcp-data` shared between Luna Host and Decoupled MCP Server, managing client credentials, tagged webhooks, and WAL concurrency without exposing database tables across the interface seam.
_Avoid_: SQLite helper, MCP database layer, shared DB scripts

**MCP Server Plugin**:
A self-contained capability module hosted by `Decoupled MCP Server` that registers MCP tools and optional HTTP routes without depending on Discord Gateway or Kord.
_Avoid_: MCP addon, tool handler, bot plugin

**Ledger Entry**:
A single-entry personal income or expense record owned by a user—carrying an integer minor-unit amount, ISO-4217 currency code, normalized category, optional note and payment method tag, and occurrence timestamp—with a soft-delete lifecycle.
_Avoid_: Transaction, accounting line, journal entry, expense row

**Ledger Store**:
The isolated SQLite database dedicated to persisting `Ledger Entry` records independently of `Shared MCP Data Store`, keyed by durable user identity rather than ephemeral MCP client credentials.
_Avoid_: Ledger table in luna.db, finance cache, bookkeeping DB

**Bidirectional Dialogue Slicing**:
The conversation history traversal strategy that walks both backward and forward from the target message, prioritizing backward context to establish discussion origin and extending forward until an inactivity gap, a bot summary, or the message capacity budget is encountered.
_Avoid_: Forward-backward scraper, chat window expansion

**Reply Reference Chaining**:
The selective resolution and inline attribution of Discord replied-to messages that lie beyond the standard conversation slice window, providing cross-temporal conversational context without corrupting the chronological timeline.
_Avoid_: Reply scraping, message jumping, thread follow

**Search Grounding**:
The model-controlled invocation of Google Search (`google_search` tool) via the Gemini Interactions API (`/v1beta/interactions`) across the `Summary Pipeline` and `Chatter Pipeline` to verify factual claims, look up real-time information, and define specialized terminology (alongside `url_context` when general web URLs are present).
_Avoid_: Web scraping, Google search bot, search plugin

**Grounding Source Attribution**:
The extraction of `url_citation` annotations from Interactions API `model_output` steps—excluding `url_context_result.retrieved_url` entries and original conversation slice URLs—and structured presentation of up to 3 unique external citations at the conclusion of a summary with URL embed suppression (`[Title](<URL>)` and `suppressEmbeds = true`) to prevent Discord layout pollution (omitted in `Chatter Pipeline` replies).
_Avoid_: Link spam, citation previews, embed footer

**Chatter Pipeline**:
The autonomous Discord conversation participation workflow that evaluates channel dialogue and generates concise, persona-grounded community replies.
_Avoid_: Auto-responder, Laya bot, chat spammer

**Participation Decision**:
The low-latency binary probability assessment that determines whether Luna should join an ambient Discord conversation before invoking reply generation.
_Avoid_: Laya System 1, participation scorer, AI gatekeeper

**Tiered Video Latency Policy**:
The Chatter video comprehension rule that uses static video understanding for short videos (<= 5 minutes) everywhere, while reserving slower Agentic Video Understanding (> 5 minutes) for direct mentions and falling back to creator metadata and audience feedback in ambient chats.
_Avoid_: Video timeout guard, ambient video skip

**AI Trace**:
The complete diagnostic record of a single Discord-triggered AI workflow execution across either the Summary Pipeline or Chatter Pipeline, capturing the trigger context, ordered AI Attempts, degradation path, and final outcome.
_Avoid_: AI log, LLM dump, debug log

**AI Attempt**:
A single upstream AI API request-and-response exchange executed within an AI Trace, including initial calls, fallback retries, tool continuations, and interaction polling cycles.
_Avoid_: API hit, retry log, model call record

**Owner Error Notification**:
An asynchronous Discord direct message dispatched by Luna Host to configured operator User IDs when a runtime `WARN` or `ERROR` event passes source filtering, secret masking, and deduplication.
_Avoid_: Error alert DM, crash ping, admin webhook

**Burst Debounce Window**:
The bounded per-channel coalescing interval triggered by incoming Discord messages before `Participation Decision` and reply generation, allowing newer messages to cancel and restart an active run while isolating direct mentions from unrelated bystander chatter.
_Avoid_: Typing wait, message queue, cooldown buffer

**Overlapping Reading Simulation**:
The silent reading-delay phase executed concurrently with `Participation Decision` and reply generation after the `Burst Debounce Window` closes, deferring Discord's typing indicator until generated text is ready for length-paced playback.
_Avoid_: Pre-typing sleep, fake read delay, AI wait

**Asymmetric Reply Threading**:
The delivery anchoring rule that posts unreferenced channel messages when the target message remains near the bottom of the channel and attaches a Discord reply reference to the opening segment once two or more non-Luna messages intervene, pinging the replied author only when responding to a direct mention or reply to Luna rather than ambient banter.
_Avoid_: Quote fallback, reply ping, thread anchoring

**Segmented Burst Delivery**:
The multi-turn conversational output cadence that splits a generated `Chatter Pipeline` reply into bounded sequential messages separated by brief typing pauses.
_Avoid_: Multi-line spam, message chunking, stream split

**Chatter Persona**:
Luna's distinct conversational personality in `Chatter Pipeline`—a quick-witted, curious, sharp-tongued yet approachable freelance creator / flexible homebody night-owl friend who is confident yet occasionally scatterbrained, quick to admit mistakes, mildly thin-skinned when pressed, and naturally helpful to friends without acting like an assistant—grounded by `Daily Schedule` and modulated by `Circadian Persona Drift` and per-user `User Relationship`, isolated from `Summary Pipeline`.
_Avoid_: Bot bio, assistant tone, static system prompt

**Circadian Persona Drift**:
The transient in-memory modulation of Luna's `Chatter Persona` conversational tone and effective `Participation Decision` threshold via periodic mood/energy shifts layered atop `Daily Schedule` (falling back to fixed local time-of-day periods only when `Daily Schedule` is disabled or not yet generated).
_Avoid_: Persistent mood store, sleep mode, static persona

**Silent Reaction Path**:
The zero-text participation outcome in which `Chatter Pipeline` responds to an intermediate `Participation Decision` probability band by adding a single Discord emoji reaction (preferring bot application emojis, then guild custom emojis, then standard Unicode emojis) instead of sending text, failing silently when selection yields no valid emoji.
_Avoid_: Fallback reaction pool, emoji spam, reaction bot

**Chatter Custom Emoji Policy**:
The `Chatter Pipeline` formatting and multimodal rule that strips Unicode emojis from generated text while permitting verified bot application emojis (prioritized) and custom emojis from the target Discord guild, alongside raster sticker and animated GIF preview ingestion.
_Avoid_: Guild Custom Emoji Policy, Global emoji ban, raw emoji passthrough, sticker spam

**Custom Emoji Meaning**:
A concise semantic description of a Discord custom emoji derived from its visual appearance and name, cached globally by emoji ID so the `Chatter Pipeline` can interpret bot application, guild, and external custom emojis in dialogue and select contextually appropriate application or guild emojis for replies and the `Silent Reaction Path`.
_Avoid_: Emoji caption, emote tag, emoji dictionary

**Emoji Usage Ranking**:
The rolling frequency window over message occurrences and reactions used by the `Chatter Pipeline` to prioritize which bot application emojis (tracked globally) and guild custom emojis (tracked per guild) enter their respective bounded prompt selection pools ahead of deterministic backfill.
_Avoid_: Emoji leaderboard, popular emotes, top-50 cache

**Working Memory Window**:
The recency- and count-bounded short-term dialogue history assembled for `Chatter Pipeline` context—retaining third-party bot messages and resolved `Reply Reference Chaining`—aligned with the recency exclusion boundary of `Long-Term Memory`.
_Avoid_: STM buffer, chat cache, sliding log

**Long-Term Memory**:
The guild-isolated store of persistent `Atomic Fact` records (queried on demand) and `Social Understanding` records (pre-injected for active participants) scoped to either an individual user or the shared guild within `Chatter Pipeline`.
_Avoid_: LTM dump, user profile DB, RAG store, channel memory

**Atomic Fact**:
A self-contained, pronoun-resolved factual statement distilled from channel dialogue—carrying an importance rating, retrieval keywords, and an optional `Fact Expiration`—that captures a durable user preference, core fact, or time-bound event without relying on surrounding messages for meaning.
_Avoid_: Memory snippet, chat summary, factoid

**Social Understanding**:
A stable, non-decaying relational or behavioral impression distilled from recurring dialogue—capturing how Luna interacts with a specific user or a guild's shared norms and humor—incrementally refined rather than overwritten as isolated events.
_Avoid_: Memory understanding, user persona, impression note, profile summary

**Understanding Pre-Injection**:
The zero-embedding reply-time inclusion of compact `Social Understanding` records for the triggering author, human users mentioned in the latest message, recent `Working Memory Window` speakers, and the active guild within a bounded character budget, providing immediate familiarity without invoking `Agentic Memory Retrieval`.
_Avoid_: Pre-prompt RAG, memory dump, profile injection

**Fact Expiration**:
An optional calendar-date expiry assigned during `Memory Distillation` to time-bound `Atomic Fact` records so ephemeral events are excluded from retrieval and purged once elapsed while durable facts remain governed by importance-aware LRU eviction.
_Avoid_: Global TTL, blanket memory expiry, inactivity purge

**Memory Distillation**:
The asynchronous background workflow that extracts `Atomic Fact` records and refines `Social Understanding` impressions from unprocessed channel dialogue once inactivity or batch thresholds are reached, reconciling changes against existing `Long-Term Memory`.
_Avoid_: Memory scraper, chat cron, background summarizer

**Distillation Cursor**:
The per-channel watermark recording the latest Discord message ID already processed by `Memory Distillation`, enabling incremental history fetching without persisting raw chat logs.
_Avoid_: Message offset, sync checkpoint, log pointer

**Agentic Memory Retrieval**:
The model-driven tool invocation inside `Chatter Pipeline` reply generation that searches `Long-Term Memory` on demand with context-rewritten queries, hybrid lexical-semantic ranking, `Recency Decay`, recency exclusion, and bounded turn and timeout guardrails.
_Avoid_: Pre-prompt RAG, memory injection, auto-recall

**Recency Decay**:
A bounded dual-signal damping factor applied during `Agentic Memory Retrieval` ranking—blending last-updated age and last-recalled age—so fresher or frequently recalled `Atomic Fact` records stay prominent without burying durable high-relevance preferences (exempting `Social Understanding`).
_Avoid_: Time cutoff, hard age filter, linear score penalty

**Dialogue Context Engine**:
The policy-driven shared module in `luna.core.ai` that provides unified Discord noise evaluation, external `Reply Reference Chaining`, `Mention Resolution`, guild display-name resolution, policy-gated relative time-gap markers, embed and Tenor/Giphy GIF preview formatting, primary social/YouTube link enrichment (with 2-hour duration cap and regex fallback), and image marker formatting for both `Chatter Pipeline` and `Summary Pipeline` without cross-plugin coupling. All Discord-to-LLM message formatting entry points (`DiscordDialogueContextEngine`, `ChatterContextCollector`, `DeferredCatchUpService`) route through its formatting and resolution seams.
_Avoid_: Context helper, shared collector, dialogue utils, enrichment service

**Mention Resolution**:
The inline expansion of raw Discord user, role, and channel mention tokens into human-readable display names within a formatted dialogue transcript—preserving stable user IDs in `Chatter Pipeline` while omitting IDs in `Summary Pipeline`—without altering the original Discord message content.
_Avoid_: Mention stripping, tag replacement, ID scrubbing

**Daily Schedule**:
Luna's globally shared, day-scoped 24-hour sequence of contiguous `Schedule Block` entries generated from `Chatter Persona`, day-of-week, prior-day continuity, and any `Schedule Commitment` entries for that date, governing `Chatter Pipeline` availability and Discord presence and remaining mutable from the current time onward via `Agentic Schedule Adjustment`.
_Avoid_: Bot calendar, cron timetable, sleep mode, static routine

**Schedule Block**:
A single non-overlapping time interval within a `Daily Schedule` carrying an activity title, contextual detail, Discord status text, a `Presence Activity Type`, an `Availability State`, and whether it was conversationally adjusted.
_Avoid_: Time slot, event item, activity chunk

**Presence Activity Type**:
The Discord activity presentation category assigned to a `Schedule Block` (`CUSTOM`, `PLAYING`, `LISTENING`, `WATCHING`), determining whether Luna's status renders as a free-form custom status or a native Discord activity.
_Avoid_: Status kind, rich presence mode, game tag

**Availability State**:
The four-tier responsiveness classification of a `Schedule Block` (`AVAILABLE`, `BREAK`, `BUSY`, `SLEEPING`) that determines whether `Chatter Pipeline` replies in real time, defers incoming messages, triggers `Deferred Catch-Up`, and sets Luna's Discord presence indicator.
_Avoid_: Busy flag, online mode, DND switch

**Agentic Schedule Adjustment**:
The model-driven tool capability within `Chatter Pipeline` that modifies the remaining future portion of today's `Daily Schedule` or manages upcoming `Schedule Commitment` entries in response to group conversation, subject to persona judgment and duration/rate guardrails.
_Avoid_: Schedule hack, chat calendar edit, live timetable override

**Schedule Commitment**:
A globally shared, cross-day planned activity booked via conversation for an upcoming local calendar date within a bounded horizon, visible to Luna and `/schedule` ahead of time and anchored into that date's `Daily Schedule` upon day rollover.
_Avoid_: Future event, calendar invite, reminder note, deferred task

**Deferred Catch-Up**:
The selective backlog review executed when Luna transitions from `BUSY` or `SLEEPING` into `BREAK` or `AVAILABLE`, inspecting bounded missed channel dialogue to reply to or silently react to only a subset of messages (prioritizing direct mentions) rather than answering everything.
_Avoid_: Backlog dump, missed-message replay, unread queue flush

**Catch-Up Watermark**:
The per-channel persisted boundary tracking pending missed activity during unavailable blocks and the latest Discord message ID already reviewed by `Deferred Catch-Up`, preventing duplicate replies across restarts.
_Avoid_: Unread pointer, backlog offset, catch-up cursor

**User Relationship**:
The guild-scoped interpersonal stance Luna maintains toward a specific Discord user—combining a bounded `Affinity Score` mapped to an `Affinity Tier` with a concise subjective feeling and tone cue—layered atop `Social Understanding` inside `Chatter Pipeline`.
_Avoid_: Friendship level, reputation score, user rating, favorability stat

**Affinity Score**:
A bounded signed integer (`-100..+100`, default `0`) representing Luna's cumulative like or dislike toward a user within a guild, incrementally adjusted during `Memory Distillation` with per-batch bounds and subject to asymmetric `Negative Affinity Decay`.
_Avoid_: Trust points, karma, favorability number, relationship XP

**Affinity Tier**:
The five-level discrete classification derived from a user's `Affinity Score` (`DISLIKED`, `COOL`, `NEUTRAL`, `WARM`, `FAVORITE`) that governs `Participation Decision` threshold offsets, `Silent Reaction Path` suppression, `Deferred Catch-Up` prioritization, and baseline conversational warmth.
_Avoid_: Relationship rank, mood tier, friendliness level

**Negative Affinity Decay**:
The asymmetric time-based relaxation rule whereby negative `Affinity Score` values slowly drift back toward neutral (`0`) after an inactivity cooldown so strained relationships can recover, while positive scores remain durable without automatic decay.
_Avoid_: Relationship reset, score expiration, global affinity TTL

**Selective Mention Disengagement**:
The low-affinity response option for `COOL` or `DISLIKED` users who directly mention or reply to Luna, allowing `Chatter Pipeline` reply generation to inspect full dialogue context before choosing a curt/aloof reply, an explicit refusal, a cold `Silent Reaction Path`, or silent non-response.
_Avoid_: Mention ban, user blocklist, hard ignore filter

**Conversation Momentum**:
The transient per-channel conversational state initiated by a `Direct Interaction` reply that softens the flat post-reply cooldown to a minimal anti-spam floor and lowers the `Participation Decision` threshold for a bounded window and consecutive ambient turn cap so Luna can sustain natural invited back-and-forth exchanges without requiring repeated pings.
_Avoid_: Chat streak, rapid-fire mode, cooldown bypass, sticky reply mode

**Ambient Fatigue**:
The transient per-channel sliding-window threshold penalty accumulated by uninvited ambient `Chatter Pipeline` text replies—raising both the `Participation Decision` and `Silent Reaction Path` thresholds until inactivity or a `Direct Interaction` resets it—so Luna naturally steps back instead of dominating ongoing channel dialogue.
_Avoid_: Spam penalty, reply limit, chatter throttle, backoff timer

**Direct Interaction**:
An incoming Discord message in `Chatter Pipeline` explicitly addressed to Luna—either an `@Luna` mention or a ping-off Discord reply to a non-summary Luna message—that bypasses ambient channel cooldowns and `Participation Decision` while still routing `COOL` or `DISLIKED` users through `Selective Mention Disengagement`.
_Avoid_: Bot ping, forced trigger, direct chat, mention-only trigger

