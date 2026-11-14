# Gemini Interactions API Migration, URL Context Adoption, and Strict No-Fallback Tool Execution

Status: accepted

## Context

Previously, Luna's `Summary Pipeline` (`GeminiSummarizer`) and `Chatter Pipeline` (`ChatterGenerator`) relied on a split architecture:
1. **Legacy `:generateContent` Endpoint**: Both pipelines targeted `POST /v1beta/models/{model}:generateContent` with `file_data.file_uri` multimodal parts, `{"googleSearch": {}}` grounding, and `temperature` config, while only long YouTube videos (`> 5m`) in `Agentic Video Understanding` used `POST /v1beta/interactions` via `GeminiInteractionClient`.
2. **Client-Side Web Scraping (`Generic Web Resolver`)**: General public website URLs in Discord discussions were resolved by `GenericWebResolver` in `SocialPeek`, which struggled with modern dynamic websites and anti-bot protections, resolved only a single trailing `primaryUrl`, and ignored additional web links shared in the same `Conversation Slice` or chat window.
3. **Silent Layered Fallback Retries**: On upstream tool or multimodal errors, `GeminiSummarizer` and `ChatterGenerator` silently retried with stripped tools or media (`no-search`, `retryBaseline`, `no-images`, `no-media`, and Agentic-timeout baseline retries), masking real `google_search`, `url_context`, and multimodal ingestion failures in operations.

## Decision

1. **Migrate `Summary Pipeline` and `Chatter Pipeline` to `POST /v1beta/interactions`**:
   - Unify `GeminiSummarizer`, `ChatterGenerator`, and `GeminiInteractionClient` onto `POST {baseUrl}/v1beta/interactions` using the `steps` response schema, removing deprecated `temperature` and the obsolete `Api-Revision` header while retaining `generation_config.max_output_tokens` (`1024` for Summary, `512` for Chatter).
   - Deliver `CDN Direct Ingestion` images via Interactions `type: "image"` blocks (`uri`, `mime_type`) placed before `text` input blocks rather than `:generateContent` `file_data.file_uri`.
   - Execute text, image, and `static` short-video requests synchronously (`background = false`), reserving asynchronous polling (`background = true` + `GET /v1beta/interactions/{id}`) for `Agentic Video Understanding` (`> 5m`).
   - Implement `fetch_earlier_messages` lookback in `Summary Pipeline` via stateful `previous_interaction_id` and `type: "function_result"` (`name`, `call_id`, `result`) continuations, re-supplying `system_instruction`, `tools`, and `generation_config`.
   - Parse `google_search_result.result` and `url_context_result.result` as polymorphic `JsonElement?` (supporting both `JsonObject` and `JsonArray`) and map `usage` / `usage_metadata` fields (`input_tokens`, `output_tokens`, `total_tokens`, `thoughts_tokens`, `tool_use_input_tokens`) into `GeminiUsageMetadata`.

2. **Retire `Generic Web Resolver` from AI Pipelines and Adopt Gemini `url_context`**:
   - Configure `SocialPeekFactory.buildClient()` with `includeDefaultResolvers(false)` and register only social platform resolvers (`FacebookResolver`, `BilibiliResolver`, `XResolver`, `InstagramResolver`, `ThreadsResolver`, `RedditResolver`) plus `YouTubeResolver`, excluding `GenericWebResolver` from `Summary Pipeline` and `Chatter Pipeline` (while preserving `GenericWebResolver` inside `:libs:socialpeek` and `SocialPreviewPlugin`).
   - Introduce `ContextUrlClassifier` in `luna.core.ai` to extract, normalize, and partition all `URL_REGEX`-matched HTTP(S) URLs into `SOCIAL`, `YOUTUBE`, `DISCORD_CDN`, and `WEB` (`webUrls`, ordered and deduplicated, capped at `MAX_WEB_URLS = 20`).
   - Preserve general public web URLs verbatim in dialogue transcripts and attach `{"type": "url_context"}` whenever `GEMINI_URL_CONTEXT_ENABLED` is `true` (default `true`, alias `gemini.urlContextEnabled`) and `webUrls` is non-empty—even when Discord attaches OpenGraph `[連結預覽: ...]` embeds. Requests containing only `SOCIAL`, `YOUTUBE`, or `DISCORD_CDN` links never attach `url_context`.

3. **Coexist with `google_search` and Filter `url_citation` Grounding Annotations**:
   - Attach `{"type": "google_search"}` when `GEMINI_SEARCH_GROUNDING_ENABLED` is `true` alongside `{"type": "url_context"}` (when `webUrls` is non-empty) and `{"type": "function", "name": "fetch_earlier_messages", ...}` (in `Summary Pipeline` when `lookbackProvider != null`).
   - Extract `url_citation` annotations from `model_output` steps and exclude normalized URLs matching original slice/transcript URLs (`allOriginalUrls` / `inputUrls`) and `url_context_result.retrieved_url` entries, capping deduplicated `Grounding Source Attribution` citations at 3 (`[Title](<URL>)` with `suppressEmbeds = true` in `Summary Pipeline`; omitted in `Chatter Pipeline`).

4. **Retain `YouTubeResolver` Metadata/Comments + Native Video Understanding**:
   - Retain `YouTubeResolver` for `durationSeconds` (`Adaptive Video Processing` and `Tiered Video Latency Policy`), `Creator Description Ingestion`, and `Audience Feedback Injection`, while passing video streams via Interactions `type: "video"` (`processing: "static" | "agentic"`) and explicitly **omitting `mime_type`** for YouTube URIs (`isYouTubeUri`). Fall back to URL regex `static` Video Understanding when `YOUTUBE_API_KEY` is absent or `YouTubeResolver` fails.

5. **Keep `GeminiDecisionClient` on `/v1beta/decisions`**:
   - `Participation Decision` (`gemini-decision-1`) remains on `POST /v1beta/decisions`, retaining its single `DECISION:multimodal` to `DECISION:text-only` fallback on multimodal failure and failing closed on upstream error.

6. **Enforce Strict No-Fallback Tool/Media Execution and Extended `AI Trace` Observability**:
   - Remove layered degraded retry branches (`no-search`, `retryBaseline`, `no-images`, `no-media`, and Agentic-timeout baseline retries) from `GeminiSummarizer` and `ChatterGenerator`, while preserving pre-request duration policy (`Tiered Video Latency Policy` omitting `videoUri` for ambient `Chatter` `> 5m` videos, and `7200s` 2-hour cap across both pipelines).
   - On single-attempt upstream, tool, safety, or empty-output failure, emit `logger.error`, mark `AI Trace` `FAILED`, and return `SummaryResult.Error` or `null`.
   - When `url_context_result` reports a non-success or `"unsafe"` status, emit `logger.error` with `interactionId`, `retrieved_url`, and `status` and record `urlContextResults` in `AI Trace`, while preserving valid `model_output` if the model still produced a usable response.
   - Extend `AiTraceModels` with `AiAttemptRequest.webUrlCount`, `AiAttemptRequest.enabledTools`, `AiAttemptRecord.urlContextResults`, `googleSearchQueries`, `rawCitations`, and `filteredCitations`.

## Superseded Decisions

This decision supersedes the following portions of prior ADRs:
- **ADR 0009 (`0009-bidirectional-dialogue-slicing-and-search-grounding.md`)**: Supersedes `:generateContent` `{"googleSearch": {}}` tool wiring, `groundingMetadata.groundingChunks` extraction (replaced by `/v1beta/interactions`, `{"type": "google_search"}`, and `model_output` `url_citation` filtering), and Section 5's automatic fallback from Search Grounding to baseline text summarization. Bidirectional Dialogue Slicing, Cross-Temporal Reply Chaining, strict source isolation, and Discord embed-suppressed citation formatting remain active.
- **ADR 0010 (`0010-gemini-decision-and-multimodal-chatter.md`)**: Supersedes `ChatterGenerator`'s `:generateContent` `static` YouTube invocation, SocialPeek `GenericWebResolver` scraping for `primaryUrl`, `file_data` URI parts, and sequential `ChatterGenerator` degradation (stripping `googleSearch` and `file_data`). `GeminiDecisionClient` on `/v1beta/decisions` (with its single multimodal-to-text retry), `Lightweight-Heavy Multimodal Split`, `Tiered Video Latency Policy` for `> 5m` videos, and shared `luna.core.ai` seam boundaries remain active.
- **ADR 0001 / ADR 0003 / ADR 0005**: `GenericWebResolver` is retired from AI pipelines (retained in `:libs:socialpeek` and `SocialPreviewPlugin`), and `:generateContent` `file_data.file_uri` / image-failure text fallback in `Summary Pipeline` are replaced by Interactions `type: "image"` blocks and strict no-fallback execution.

## Consequences

- **Positive**: Consolidates `Summary Pipeline` and `Chatter Pipeline` onto a single `/v1beta/interactions` client seam (`GeminiInteractionClient`); enables Gemini `url_context` to read up to 20 dynamic web pages per slice without client-side HTML scraping; separates `url_context` sources from `Search Grounding` citations; and surfaces tool/media failures immediately via `logger.error` and `AI Trace` (`FAILED`).
- **Negative / Trade-offs**: Transient failures in `google_search`, `url_context`, or CDN image ingestion during generation now fail the request immediately rather than silently returning an ungrounded or text-only reply (though `SummaryService` continues to evict transient failures from `BoundedStatusCache` so users can re-react).
