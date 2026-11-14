# Gemini Decision 1 Participation Evaluation and Multimodal Chatter Pipeline

Status: accepted

## Context

Previously, the `Chatter Pipeline` relied on an external third-party Laya Studio endpoint (`POST /v1/systemone`) with dedicated `LAYA_*` credentials for ambient `Participation Decision` checks, and only passed plain-text dialogue transcripts to both the decision engine and the Gemini reply generator. Meanwhile, the `Summary Pipeline` already supported `Chat Image Attachment` via `CDN Direct Ingestion` (ADR 0005), YouTube `Adaptive Video Processing` with `Audience Feedback Injection` (ADR 0004), `Generic Web Resolver` and social post resolution via SocialPeek, `Reply Reference Chaining`, and `Search Grounding` (ADR 0009).

Google's Gemini Developer API now provides the `gemini-decision-1` model via `POST /v1beta/decisions`, offering 0-output-token calibrated `noul` probability evaluation over multimodal `input` content blocks using the same `GEMINI_API_KEY` and `GEMINI_BASE_URL`.

## Decision

1. **Replace Laya Studio with Gemini Decisions API (`gemini-decision-1`)**:
   - Retire all Laya Studio integrations and `LAYA_*` environment variables completely.
   - Evaluate ambient `Participation Decision` requests via `POST {GEMINI_BASE_URL}/v1beta/decisions` using `x-goog-api-key` authentication, `model: "gemini-decision-1"`, and a single `noul` question (`should_participate`).
   - Trigger participation when `answer.noul >= CHATTER_DECISION_THRESHOLD` (default `0.7`), rather than comparing `confidence` (which equals `max(noul, 1 - noul)` and can be high for strong rejections).
   - Enable the `Chatter Pipeline` whenever `GEMINI_API_KEY` is configured (unless explicitly disabled via `CHATTER_ENABLED=false`).

2. **Lightweight-Heavy Multimodal Split**:
   - Permit image-only and URL-only messages through `ChatterNoiseFilter` decision pre-checks.
   - Supply `gemini-decision-1` with recent dialogue turns (last 3 messages), Discord link previews, and up to 3 `Chat Image Attachment` blocks at `resolution: "low"` using the Interactions-style `input` block schema (`type: "image"`, `uri`, `mime_type`, `resolution`). Since `v1beta/decisions` does not support YouTube URLs or search tools, heavy web scraping and video comprehension are deferred until participation is confirmed or Luna is directly mentioned.
   - Supply the reply generator with the broader 15-message window including `Reply Reference Chaining`, Discord `embeds`, up to `SUMMARY_MAX_IMAGES` (default 10) `Chat Image Attachment` parts, resolved `primaryUrl` content via SocialPeek (latest URL in the window), and `Search Grounding` (respecting `GEMINI_SEARCH_GROUNDING_ENABLED`, without emitting formal summary headers or citation lists).

3. **Tiered Video Latency Policy (Adapting ADR 0004 for Real-Time Chat)**:
   - Contradicts unconditional `Agentic Video Understanding` for $> 5$-minute videos in ADR 0004, which is acceptable for reaction-triggered summaries but introduces 5–20s polling latency unsuitable for ambient chat interjections.
   - For YouTube links $\le 5$ minutes, pass `videoUri` via `generateContent` (`static`) in both ambient and direct-mention replies.
   - For YouTube links $> 5$ minutes (up to 2 hours), ambient replies degrade to `Creator Description Ingestion` and `Audience Feedback Injection` for fast response, while direct `@Luna` mentions invoke `Agentic Video Understanding` (`/v1beta/interactions` polling).

4. **Cross-Plugin Shared Core Seam & Observable Degradation**:
   - Extract shared Gemini API models, image attachment extraction/validation, `SocialPeek` factory wiring, and agentic interaction polling into a shared core module so `chatter` and `summarizer` remain isolated `Luna Plugin` implementations without peer-plugin dependencies.
   - Both `GeminiDecisionClient` and `ChatterGenerator` fail gracefully with observable warning logs: `GeminiDecisionClient` retries text-only `input` if multimodal CDN image fetching fails and fails closed on upstream errors; `ChatterGenerator` degrades sequentially by stripping `googleSearch` and `file_data` on multimodal/tool failures.

## Consequences

- **Positive**: Consolidates AI credentials onto `GEMINI_API_KEY` and `GEMINI_BASE_URL`; enables Chatter to understand chat screenshots, memes, YouTube videos, social posts, and web pages on par with the `Summary Pipeline`; preserves low latency on ambient channel messages.
- **Negative / Trade-offs**: Ambient Chatter replies on long ($> 5$m) YouTube videos rely on metadata and top comments rather than full audiovisual stream navigation unless Luna is directly mentioned.
