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

