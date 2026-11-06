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
A resolver in the SocialPeek library that extracts title, main textual content, and metadata from arbitrary non-social websites.
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

**Dual-Boundary Backtracking**:
The history traversal algorithm that walks backward from the reaction target message up to a configurable upper bound (default 50 messages, configurable via `SUMMARY_MAX_HISTORY_MESSAGES`), halting if an inactivity gap (default 15 minutes, configurable via `SUMMARY_INACTIVITY_GAP_MINUTES`), accumulated character budget, or a previous bot summary is encountered.
_Avoid_: Reverse history search, boundary walk, message scraper

**Dialogue Noise Filter**:
The preprocessing stage that purges slash commands, custom emojis, system events, and ambient third-party bots from a conversation slice while preserving short human conversational turns and media attachments.
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
A stateless, framework-independent utility library residing in `libs/` that is shared across two or more plugins (such as SocialPeek) or targeted for standalone external distribution.
_Avoid_: Helper module, common plugin, shared code

**Chat Image Attachment**:
An image file (`image/jpeg`, `image/png`, `image/webp`, `image/gif`) attached to a Discord message within a Conversation Slice, ingested as visual context for the Summary Pipeline.
_Avoid_: Picture upload, chat photo, message media

**CDN Direct Ingestion**:
The multimodal delivery strategy of passing Discord's signed CDN attachment URL directly to Gemini API's `file_data.file_uri`, avoiding server-side binary proxying.
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

