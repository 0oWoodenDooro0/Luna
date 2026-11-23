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
The delivery anchoring rule that posts unreferenced channel messages when the target message remains near the bottom of the channel and attaches a Discord reply reference to the opening segment once two or more non-Luna messages intervene.
_Avoid_: Quote fallback, reply ping, thread anchoring

**Segmented Burst Delivery**:
The multi-turn conversational output cadence that splits a generated `Chatter Pipeline` reply into bounded sequential messages separated by brief typing pauses.
_Avoid_: Multi-line spam, message chunking, stream split

**Chatter Persona**:
Luna's distinct conversational personality in `Chatter Pipeline`—a quick-witted, curious, sharp-tongued yet approachable friend who is confident yet occasionally scatterbrained, quick to admit mistakes, mildly thin-skinned when pressed, and naturally helpful without acting like an assistant—modulated by `Circadian Persona Drift` and isolated from `Summary Pipeline`.
_Avoid_: Bot bio, assistant tone, static system prompt

**Circadian Persona Drift**:
The transient in-memory modulation of Luna's `Chatter Persona` conversational tone and effective `Participation Decision` threshold across local time-of-day periods and periodic mood/energy shifts.
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
The guild-isolated store of persistent `Atomic Fact` records scoped to either an individual user or the shared guild within `Chatter Pipeline`, queried on demand rather than unconditionally pre-injected into every reply.
_Avoid_: LTM dump, user profile DB, RAG store, channel memory

**Atomic Fact**:
A self-contained, pronoun-resolved factual statement distilled from channel dialogue capturing a durable user preference, core fact, or time-bound event (with an optional `Fact Expiration`) without relying on surrounding messages for meaning.
_Avoid_: Memory snippet, chat summary, factoid

**Fact Expiration**:
An optional calendar-date expiry assigned during `Memory Distillation` to time-bound `Atomic Fact` records so ephemeral events are excluded from retrieval and purged once elapsed while durable facts remain governed by LRU eviction.
_Avoid_: Global TTL, blanket memory expiry, inactivity purge

**Memory Distillation**:
The asynchronous background workflow that extracts `Atomic Fact` records from unprocessed channel dialogue once inactivity or batch thresholds are reached, reconciling new facts against existing `Long-Term Memory` via insert, update, or delete operations.
_Avoid_: Memory scraper, chat cron, background summarizer

**Distillation Cursor**:
The per-channel watermark recording the latest Discord message ID already processed by `Memory Distillation`, enabling incremental history fetching without persisting raw chat logs.
_Avoid_: Message offset, sync checkpoint, log pointer

**Agentic Memory Retrieval**:
The model-driven tool invocation inside `Chatter Pipeline` reply generation that searches `Long-Term Memory` on demand with context-rewritten queries, hybrid lexical-semantic ranking, `Recency Decay`, recency exclusion, and bounded turn and timeout guardrails.
_Avoid_: Pre-prompt RAG, memory injection, auto-recall

**Recency Decay**:
A bounded age-based damping factor applied during `Agentic Memory Retrieval` ranking so fresher `Atomic Fact` records edge out older ones at comparable relevance without burying durable high-relevance preferences.
_Avoid_: Time cutoff, hard age filter, linear score penalty

**Dialogue Context Engine**:
The policy-driven shared module in `luna.core.ai` that provides unified Discord noise evaluation, external `Reply Reference Chaining`, embed and Tenor/Giphy GIF preview formatting, primary social/YouTube link enrichment (with 2-hour duration cap and regex fallback), and image marker formatting for both `Chatter Pipeline` and `Summary Pipeline` without cross-plugin coupling.
_Avoid_: Context helper, shared collector, dialogue utils, enrichment service

