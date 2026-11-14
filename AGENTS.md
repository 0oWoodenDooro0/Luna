# Agent Instructions

## Monorepo modules

- `src/main/kotlin/luna/`: Discord bot daemon, including host orchestrator (`plugin/`), first-party plugins:
  - `core/`: Bot core infrastructure, cross-plugin shared memory state ([`LunaWebhookRegistry`](src/main/kotlin/luna/core/LunaWebhookRegistry.kt), [`SummaryMessageRegistry`](src/main/kotlin/luna/core/SummaryMessageRegistry.kt)), and `core/ai/` shared Gemini multimodal/SocialPeek primitives ([`GeminiModels`](src/main/kotlin/luna/core/ai/GeminiModels.kt), [`GeminiInteractionClient`](src/main/kotlin/luna/core/ai/GeminiInteractionClient.kt), [`ChatImageAttachmentExtractor`](src/main/kotlin/luna/core/ai/ChatImageAttachmentExtractor.kt), [`EmojiSanitizer`](src/main/kotlin/luna/core/ai/EmojiSanitizer.kt), [`SocialPeekFactory`](src/main/kotlin/luna/core/ai/SocialPeekFactory.kt)).
  - `chatter/`: Autonomous Discord chatter plugin ([`ChatterPlugin`](src/main/kotlin/luna/chatter/ChatterPlugin.kt), [`ChatterService`](src/main/kotlin/luna/chatter/ChatterService.kt), [`ChatterNoiseFilter`](src/main/kotlin/luna/chatter/ChatterNoiseFilter.kt), [`ChatterContextCollector`](src/main/kotlin/luna/chatter/ChatterContextCollector.kt), [`GeminiDecisionClient`](src/main/kotlin/luna/chatter/GeminiDecisionClient.kt), [`ChatterGenerator`](src/main/kotlin/luna/chatter/ChatterGenerator.kt), [`ChatterPromptBuilder`](src/main/kotlin/luna/chatter/ChatterPromptBuilder.kt), [`ChatterConfig`](src/main/kotlin/luna/chatter/ChatterConfig.kt), [`ChatterDecisionConfig`](src/main/kotlin/luna/chatter/ChatterDecisionConfig.kt)).
  - `summarizer/`: Multi-modal AI summarization pipeline ([`SummaryService`](src/main/kotlin/luna/summarizer/SummaryService.kt), [`GeminiSummarizer`](src/main/kotlin/luna/summarizer/GeminiSummarizer.kt), [`GeminiPromptBuilder`](src/main/kotlin/luna/summarizer/GeminiPromptBuilder.kt), [`ConversationSliceCollector`](src/main/kotlin/luna/summarizer/ConversationSliceCollector.kt), [`DialogueNoiseFilter`](src/main/kotlin/luna/summarizer/DialogueNoiseFilter.kt)).
    - *Test architecture & ownership*:
      - [`GeminiSummarizerTest`](src/test/kotlin/luna/summarizer/GeminiSummarizerTest.kt) / [`GeminiSummarizerLookbackTest`](src/test/kotlin/luna/summarizer/GeminiSummarizerLookbackTest.kt) / [`GeminiSummarizerMultimodalGroundingTest`](src/test/kotlin/luna/summarizer/GeminiSummarizerMultimodalGroundingTest.kt): Core/agentic summarization, multi-turn lookback execution, and multimodal/grounding fallback recovery.
      - [`ConversationSliceCollectorTest`](src/test/kotlin/luna/summarizer/ConversationSliceCollectorTest.kt): Backtracking, gap halts, token/character budgeting, dialogue formatting, and media extraction.
      - [`SummaryServiceTest`](src/test/kotlin/luna/summarizer/SummaryServiceTest.kt): Discord reaction event handling, deduplication caching, status publishing, and reply delivery. Decommission obsolete mock fixtures in service tests when moving responsibilities to deep collaborator modules.
- `libs/socialpeek/`: Multi-platform social media post and video metadata resolver library.
- `libs/config/`: Shared environment and dotenv parsing, secret masking, and schema validation.
- `libs/mcp-data/`: Shared SQLite persistence layer for MCP client credentials and channel webhooks ([`McpClientRepository`](libs/mcp-data/src/main/kotlin/luna/mcp/data/McpClientRepository.kt), [`McpWebhookRepository`](libs/mcp-data/src/main/kotlin/luna/mcp/data/McpWebhookRepository.kt), [`McpDatabase`](libs/mcp-data/src/main/kotlin/luna/mcp/data/McpDatabase.kt)).
- `mcp-server/`: Standalone Model Context Protocol (MCP) server for Google Gemini Apps integration. Completely decoupled from Discord bot runtime/tokens; communicates via shared SQLite `mcp_webhooks` table ([`McpServerApplication`](mcp-server/src/main/kotlin/luna/mcp/server/McpServerApplication.kt), [`McpServerRoutes`](mcp-server/src/main/kotlin/luna/mcp/server/McpServerRoutes.kt), [`DiscordWebhookBroadcaster`](mcp-server/src/main/kotlin/luna/mcp/server/DiscordWebhookBroadcaster.kt), [`DiscordMessageChunker`](mcp-server/src/main/kotlin/luna/mcp/server/DiscordMessageChunker.kt), [`McpOAuthProvider`](mcp-server/src/main/kotlin/luna/mcp/server/McpOAuthProvider.kt)).

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for this repository. See `docs/agents/issue-tracker.md`.

### Triage labels

Canonical triage label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repository layout (`GLOSSARY.md` and `docs/adr/`). See `docs/agents/domain.md`.

### Coding standards

Judgement calls for daemon state bounds, transient error eviction, library boundaries, negative boundary coverage, and observable fallback degraded logs. See `CODING_STANDARDS.md`. Mechanical rules (daemon state collections, assertion styles, test naming, Dockerfile subproject parity, sensitive key synchronization, and cross-plugin import isolation) are checked deterministically by [`CodingStandardsTest`](src/test/kotlin/luna/core/CodingStandardsTest.kt) — run it rather than reading it end-to-end.
Unified environment configuration architecture and navigation pointers between `:root` and `:mcp-server` are documented in [`docs/architecture/configuration.md`](docs/architecture/configuration.md).

### AI Trace debugging

Full `Summary Pipeline` and `Chatter Pipeline` AI request/fallback/response traces are written as daily JSONL files at `data/logs/ai/ai-YYYY-MM-DD.jsonl` (one line per Discord trigger, cross-referenced via `traceId` in `data/logs/luna-json.log` under `layer: "AI_TRACE"`). See [`docs/architecture/ai-tracing.md`](docs/architecture/ai-tracing.md) for the complete JSONL schema, environment variables, and copy-pasteable `jq`/`rg` diagnostic queries.

### Library & dependency exploration

Inspect library APIs and dependencies directly via their `build.gradle.kts` declarations, source/interface definitions, or pre-documented cheatsheets (such as [`docs/kord-recipes.md`](docs/kord-recipes.md)) rather than running shell classpath extraction scripts or decompiling JARs with `javap`.

## Git & Conflict Resolution

- **Targeted Conflict Localization**: When integrating parallel branches or resolving merge conflicts, never guess line numbers or page through file chunks with `view_file`. Immediately run:
  ```bash
  git diff --name-only --diff-filter=U
  grep -n -E "^(<<<<<<<|=======|>>>>>>>)" <conflicted-file>
  ```
  to locate exact conflict markers and apply targeted replacements.

## Verification

- **Subagents & Inner development loop (strictly targeted tests)**: Subagents and developers iterating on code must run only narrow targeted tests with `--console=plain -q` to keep output compact. Keep large test files (`>500` lines) modularized by concern and locate symbols with `rg -n` + targeted line ranges instead of reading whole suites. Never run full monorepo `./gradlew test` during ticket implementation or TDD cycles:
  ```bash
  ./gradlew test --tests "luna.mcp.*" --console=plain -q
  ./gradlew :mcp-server:test --tests "luna.mcp.server.*" --console=plain -q
  ./gradlew test --tests "luna.summarizer.*" --console=plain -q
  ./gradlew :mcp-server:test --console=plain -q
  ./gradlew :libs:socialpeek:test --console=plain -q
  ```
- **Pre-Review / Pre-commit check (full suite & formatting)**: Run `./gradlew formatKotlin` before `./gradlew lintKotlin test --console=plain -q` (or `./gradlew check --console=plain -q`) across all modules only after completing an implementation slice or addressing all review findings, before dispatching the Review Gate or creating a PR. Pre-commit hooks in `.githooks/` run lint and tests automatically.
- **CI parity (Docker build)**: Run `docker build -t luna:ci-test .` strictly as the final pre-PR/pre-merge verification step to ensure parity with CI (`.github/workflows/ci.yml`). Banned from inner-loop iteration.
