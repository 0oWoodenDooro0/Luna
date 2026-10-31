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

