# Bidirectional Dialogue Slicing, Cross-Temporal Reply Chaining, and Autonomous Search Grounding

Status: partially superseded by [ADR 0012](0012-interactions-api-url-context-and-no-fallback.md) (`:generateContent` `googleSearch` schema, `groundingChunks` extraction, and Section 5 baseline fallback retries are replaced by `/v1beta/interactions`, `url_citation` filtering, and strict no-fallback execution)

## Context

Prior to this decision, the `Summary Pipeline` in Luna possessed two architectural limitations when summarizing Discord chat rooms:
1. **Unidirectional History Backtracking**: `ConversationSliceCollector` solely performed backward history collection from the reaction target message (`getMessagesBefore`). If a member reacted with a `Summary Reaction` at the beginning or middle of a conversation, all subsequent discussion, arguments, and conclusions were completely excluded from the context. Furthermore, non-linear replies targeting messages across the 15-minute inactivity gap were severed.
2. **Lack of Fact-Checking and Grounding**: When chat discussions revolved around breaking events, unfamiliar domain terminology, or conflicting factual statements, Gemini generated summaries based strictly on internal model priors, occasionally producing hallucinations or failing to clarify terms for participants.

## Decision

1. **Bidirectional Dialogue Slicing (`Bidirectional Dialogue Slicing`)**:
   - `ConversationSliceCollector` expands from purely backward traversal to bidirectional window exploration.
   - Slicing prioritizes backward context first to establish the origin and background of the discussion up to the inactivity gap (15 minutes), a prior bot summary, or capacity exhaustion.
   - Remaining message capacity within `SUMMARY_MAX_HISTORY_MESSAGES` (default 50) is allocated to forward exploration (`getMessagesAfter`) until encountering an inactivity gap, a subsequent bot summary, or budget exhaustion.
   - Collected messages are merged and sorted strictly into chronological order.

2. **Cross-Temporal Reply Reference Chaining (`Reply Reference Chaining`)**:
   - When traversing messages in a slice, if a message references an earlier message (`referencedMessage`) that falls outside the current temporal window, the collector fetches the referenced message (capped at a maximum of 3 unique external references per slice).
   - Injected into the transcript via inline attribution: `[UserB 回覆 UserA「<excerpt>」]: <content>`, preserving attribution context without corrupting the linear chronological timeline. Excerpts are clamped to 40 characters.

3. **Autonomous Google Search Grounding (`Search Grounding`)**:
   - When `GEMINI_SEARCH_GROUNDING_ENABLED` is true (default: true) and `config.isConfigured` is verified, `GeminiSummarizer` includes `GeminiTools.GOOGLE_SEARCH` (`{"googleSearch": {}}`) in the `tools` array of `generateContent` requests alongside function calling declarations.
   - Gemini autonomously decides whether to search Google based on conversation context to verify claims or explain specialized terminology.

4. **Structured Grounding Output & Embed Suppression (`Grounding Source Attribution`)**:
   - `GeminiPromptBuilder` maintains strict source isolation: discussion consensus belongs in `TL;DR:`, `核心重點:`, and `參與者觀點:`. When search grounding is invoked, a dedicated `名詞與事實查核:` section is appended.
   - Extracted `groundingMetadata.groundingChunks` are sanitized and capped at the top 3 unique web sources formatted with Discord markdown link syntax wrapped in angle brackets (`[Title](<URL>)`).
   - The message sender sets `suppressEmbeds = true` on the Discord reply, eliminating OpenGraph preview cards and keeping the chat view clean.

5. **Dual-Tool Orchestration & Graceful Degradation**:
   - Request tool arrays cleanly separate function declarations from the `googleSearch` tool object.
   - If a function call (`fetch_earlier_messages`) is triggered, history lookback executes normally with tools maintained in subsequent turns.
   - If Search Grounding fails due to API quota, transient network timeout, or schema incompatibility, the summarizer automatically falls back to baseline text summarization.
