# YouTube Multimodal Video Understanding and Audience Comments Summarizer

## Context
When Discord users share YouTube video URLs and trigger a Summary Reaction, the existing `SocialPeek` implementation only queries the YouTube `oEmbed` endpoint, returning only the video title as plain content without retrieving video transcripts, visual/audio content, or user discussions.

Google Gemini API natively supports public YouTube URLs (`file_uri: "https://www.youtube.com/watch?v=..."`) for multimodal video understanding. Furthermore, Gemini API introduces two processing modes:
1. **Static Processing (`static`)**: Samples frames at 1 FPS with audio. Ideal for short videos (< 5 minutes) with fast time-to-first-token (TTFT).
2. **Agentic Processing (`agentic`)**: Dynamically navigates the video, selectively loading transcript segments, visual frames, or audio as needed via internal processing calls. This reduces token consumption by up to 88% on longer videos.

However, Gemini's multimodal video understanding strictly parses video and audio streams; it does not read YouTube comments or live chat. To produce a holistic summary that captures both the creator's video content and the community's reaction, the system must independently retrieve top comments and inject them into the summarization pipeline.

## Decision

1. **Adaptive Video Processing Mode (`AdaptiveVideoProcessor`)**:
   - For YouTube URLs, the pipeline inspects video metadata (duration) and dynamically configures the Gemini request:
     - **Short Videos ($\le$ 5 minutes / 300 seconds)**: Configured with `processing: "static"` to deliver instant responses without multi-step agent deliberation latency.
     - **Medium/Long Videos (> 5 minutes, up to safety limit e.g. 120 minutes)**: Automatically switched to `processing: "agentic"`, enabling dynamic video navigation, saving up to 88% token consumption while maintaining deep comprehension of long lectures, podcasts, and presentations.
     - **Hard Duration Ceiling**: Videos exceeding 120 minutes return an early polite notice or fallback to transcript-only mode to prevent quota exhaustion.

2. **Audience Feedback Extraction via YouTube Data API v3**:
   - `YouTubeResolver` in `:libs:socialpeek` is upgraded (or backed by `YouTubeDataClient`) to call `commentThreads.list`:
     - Parameters: `part=snippet`, `order=relevance`, `maxResults=25`, `textFormat=plainText`.
     - Pinned comments and top-voted comments are extracted as structured discussion items (`Comment(author, text, likeCount)`).
     - If the YouTube API key is missing or quota is exhausted, the resolver gracefully degrades to video-only summarization without failing the pipeline.

3. **Unified Multimodal + Comments Prompt Architecture**:
   - `GeminiPromptBuilder` and `GeminiSummarizer` are extended to support multimodal parts alongside text:
     - Part 1: Video URI reference with adaptive processing mode configuration:
       ```json
       {
         "type": "video",
         "uri": "https://www.youtube.com/watch?v=...",
         "processing": "static" | "agentic"
       }
       ```
     - Part 2: Formatted text instructions incorporating the video title, URL, author note, and fetched audience comments.
   - Output format remains strict Taiwanese Traditional Chinese (zh-TW), emoji-free, conforming to:
     - `TL;DR:` 1-2 sentence core conclusion.
     - `核心重點:` 3-5 bullet points covering the video's arguments and demonstrations.
     - `社群觀點:` 2-3 bullet points synthesizing top audience feedback, consensus, or controversies.

4. **Ktor Client & Interactions API Integration**:
   - `GeminiSummarizer` adds support for Gemini's Interactions API endpoint (`/v1beta/interactions`) when `agentic` processing or asynchronous background execution is triggered, polling status until completion while respecting Discord interaction timeouts.

## Consequences

### Positive
- **Complete Visual & Audio Understanding**: Can summarize slides, code snippets, visual demos, and spoken audio in YouTube videos without requiring manual transcription or OCR.
- **Token & Cost Efficiency**: The 5-minute adaptive cutoff prevents token explosion on long videos via agentic navigation (up to 88% token savings).
- **Holistic Context**: Merges video substance with real audience sentiment, giving Discord users the full picture of controversial or informative videos.
- **Graceful Degradation**: Clear fallback hierarchy if YouTube comments API is unconfigured or quota-capped.

### Negative / Trade-offs
- Requires configuring a Google Cloud API key with YouTube Data API v3 enabled (in addition to or consolidated with Gemini API Key).
- Agentic processing on very long videos introduces higher latency (5-20 seconds) due to internal tool/thought iterations.
