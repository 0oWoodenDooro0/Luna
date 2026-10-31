# Multi-Message Conversation Slice Extraction and Dialogue Structuring

## Context
When Discord members trigger a Summary Reaction on a message concluding a discussion thread or link share, summarizing only the single target message often misses critical context, debate viewpoints, or preceding URLs. To produce meaningful summaries of discussions, the Summary Pipeline must incorporate preceding conversation context without pulling in stale, irrelevant chat history or ambient bot noise.

## Decision
1. **Dual-Boundary Backtracking**:
   - History retrieval fetches preceding messages from the channel up to a configurable upper bound of 50 messages by default (configurable via `SUMMARY_MAX_HISTORY_MESSAGES`, clamped between 1 and 100 to align with Discord's single REST page limit).
   - History backtracking proceeds in reverse chronological order from the target message and terminates early if:
     a. An inactivity gap between two consecutive messages exceeds a configurable duration (default 15 minutes, configurable via `SUMMARY_INACTIVITY_GAP_MINUTES`).
     b. Accumulated character budget exceeds the safety threshold (default 20,000 characters, configurable via `SUMMARY_MAX_CHARACTER_BUDGET`).
     c. A previous bot summary message (identified by `TL;DR:` or previous summary markers) is encountered.
2. **Local Dialogue Noise Filtering**:
   - Before handing conversation text to the LLM, a local noise filter strips structural noise: slash commands, bot prefixes, system messages (pins, member joins), stickers, and messages containing only custom emojis.
   - Third-party ambient bot messages are filtered out of the dialogue text without terminating the history backtracking loop.
   - Blank text messages are retained if media attachments are present, preserving multimodal discussion references.
   - Short conversational replies (`+1`, `agreed`, `lol`, `no`) are explicitly preserved with author attribution (`[User]: Content`) to preserve consensus and sentiment signals.
3. **Contextual URL Resolution**:
   - If URLs appear anywhere within the conversation slice, the pipeline extracts the most salient/recent URL and resolves its article or social post text via SocialPeek (`GenericWebResolver` / platform resolvers).
   - The resolved link text is prefixed as background context alongside the participant dialogue log.
4. **Structured Multi-Participant Output**:
   - `GeminiPromptBuilder` formats prompts for multi-message context into structured sections: `TL;DR:`, `重點摘要:` (key discussion points), and `參與者觀點:` (participant viewpoints and consensus).
   - Responses strictly maintain the emoji-free standard and truncate cleanly within Discord's 2,000-character limit.
