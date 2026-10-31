# Quoted Post Content Extraction and Representation

In social media URL resolution, users frequently share posts that quote other posts (e.g., quote tweets on X). Previously, `XResolver` only extracted the top-level tweet text, leading to empty or unhelpful AI summaries when the author provided minimal commentary or solely embedded a quoted tweet.

We decided to support Quoted Posts in `XResolver` by:
1. Adding a structured `quotedPost: PeekPost? = null` field to `PeekPost` to preserve structured boundaries.
2. Flattening formatted quoted text into `PeekPost.content` (`[引用 @<screen_name>: <quoted_text>]`) after stripping trailing quote `t.co` URLs, ensuring downstream consumers like `SummaryService` and `GeminiPromptBuilder` receive full context without breaking existing interfaces.
3. Inheriting media into `PeekPost.media` if the outer tweet has no media of its own.
4. Cross-Tweet Media Embed Fallback: When `quoted_tweet` is absent but `mediaDetails` / `entities.media` contains an `expanded_url` referencing another tweet ID (cross-tweet video/media embed), `XResolver` performs a guarded single-hop fetch (`depth = 1`, preventing recursive loops) to resolve the referenced status as `quotedPost` and format its text into `content`.

