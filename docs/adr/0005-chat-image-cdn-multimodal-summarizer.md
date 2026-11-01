# Chat Image Multimodal Summarization via Discord CDN Direct Ingestion

## Context
Discord discussions frequently involve screenshots, code snippets, visual memes, charts, or photo evidence shared alongside short conversational turns. Prior to this decision, the `Summary Pipeline` only summarized textual messages or YouTube videos, completely dropping image attachments from messages during conversation slicing.

With Google Gemini's newer multimodal models (Gemini 2.5 and 3.5 series), the Gemini API officially supports passing arbitrary public HTTPS URLs directly via `file_data.file_uri` in `generateContent` requests. Discord attachment CDN URLs (`cdn.discordapp.com`) are accessible via signed HTTPS links when fetched by Luna.

## Decision
1. **Default Model Baseline**:
   - Upgrade the default Gemini model in `GeminiConfig` from `gemini-1.5-flash` to `gemini-3.5-flash-lite`, unlocking direct HTTPS `file_uri` resolution without requiring server-side byte buffering or Base64 transcoding.

2. **CDN Direct Ingestion (`CDN Direct Ingestion`)**:
   - Instead of downloading image binaries into bot memory, Luna directly extracts Discord attachment signed URLs and injects them as `GeminiPart` objects containing `GeminiFileData(fileUri = attachment.url, mimeType = attachment.contentType)`.
   - Filters attachments strictly to supported image MIME types (`image/jpeg`, `image/png`, `image/webp`, `image/gif`).

3. **Conversational Image Slicing**:
   - Chat image attachments from both the target message and preceding messages within the `Conversation Slice` are collected chronologically.
   - Images are interleaved or correlated with conversational dialogue markers (e.g. `[Username 附帶圖片 1]`) so Gemini understands which speaker posted each visual artifact.

4. **Resource Bounds & Graceful Degradation**:
   - Impose an upper bound on the number of images per summary request (default maximum 10 images, configurable via `SUMMARY_MAX_IMAGES`), selecting the newest images within the slice if the limit is exceeded.
   - If an attachment cannot be fetched or processed by Gemini, the pipeline gracefully falls back to text-based summarization.
