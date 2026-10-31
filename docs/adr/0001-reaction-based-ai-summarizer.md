# Reaction-Based AI Summarizer with SocialPeek Generic Web Resolver

## Context
Users frequently share links (social media posts, general website articles) or long text discussions in Discord. To quickly digest content without leaving the channel, users want an AI-powered summary triggered by adding a specific emoji reaction.

## Decision
1. **Generic Web Resolver inside SocialPeek**: Instead of implementing ad-hoc web scraping in the Discord bot application, implement `GenericWebResolver` directly within `:libs:socialpeek` as a fallback `PlatformResolver`. This leverages SocialPeek's existing Ktor HTTP client, sanitizers, and Jsoup dependencies, keeping text extraction logic cohesive and reusable.
2. **Multi-Stage Content Extraction**:
   - Social media URLs: Handled by SocialPeek's platform resolvers (extracting post text and top comments where accessible, e.g., Reddit).
   - General website URLs: Handled by `GenericWebResolver` in SocialPeek using HTML readability heuristics (e.g., `<article>`, `<main>`, `<p>` text).
   - Plain text messages (no URLs): Directly uses the Discord message body text.
3. **Environment-Configured Gemini Summarizer**: Use Google Gemini API with configurable `GEMINI_API_KEY` and `GEMINI_MODEL` (defaulting to `gemini-1.5-flash`).
4. **Emoji-Free Structured Output**: The summary response strictly avoids emojis, utilizing clean Markdown headers (TL;DR, key bullet points, community sentiment if comments exist), and replies directly to the original message.
5. **Deduplication**: In-memory caching ensures that multiple reactions on the same message do not trigger redundant Gemini API invocations.
