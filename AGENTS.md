# Agent Instructions

## Monorepo modules

- `src/main/kotlin/luna/`: Discord bot daemon, including host orchestrator (`plugin/`), first-party plugins:
  - `core/`: Bot core infrastructure and cross-plugin shared memory state ([`LunaWebhookRegistry`](src/main/kotlin/luna/core/LunaWebhookRegistry.kt)).
  - `summarizer/`: Multi-modal AI summarization pipeline ([`SummaryService`](src/main/kotlin/luna/summarizer/SummaryService.kt), [`GeminiSummarizer`](src/main/kotlin/luna/summarizer/GeminiSummarizer.kt), [`GeminiPromptBuilder`](src/main/kotlin/luna/summarizer/GeminiPromptBuilder.kt), [`ConversationSliceCollector`](src/main/kotlin/luna/summarizer/ConversationSliceCollector.kt), [`DialogueNoiseFilter`](src/main/kotlin/luna/summarizer/DialogueNoiseFilter.kt)).
    - *Test architecture & ownership*:
      - [`GeminiSummarizerTest`](src/test/kotlin/luna/summarizer/GeminiSummarizerTest.kt): AI multi-turn tool calling, lookback execution, single-round lookback enforcement, and fallback recovery.
      - [`ConversationSliceCollectorTest`](src/test/kotlin/luna/summarizer/ConversationSliceCollectorTest.kt): Backtracking, gap halts, token/character budgeting, dialogue formatting, and media extraction.
      - [`SummaryServiceTest`](src/test/kotlin/luna/summarizer/SummaryServiceTest.kt) / [`SummaryServiceWebhookTest`](src/test/kotlin/luna/summarizer/SummaryServiceWebhookTest.kt): Discord reaction event handling, deduplication caching, status publishing, and reply delivery. Decommission obsolete mock fixtures in service tests when moving responsibilities to deep collaborator modules.
  - `curtly/`, `preview/`, `undercover/`: First-party feature plugins.
- `libs/socialpeek/`: Multi-platform social media post and video metadata resolver library.
- `mcp-server/`: Standalone Model Context Protocol (MCP) server for Google Gemini Apps integration. Completely decoupled from Discord bot runtime/tokens; communicates via shared SQLite `mcp_webhooks` table ([`McpServerApplication`](mcp-server/src/main/kotlin/luna/mcp/server/McpServerApplication.kt), [`McpServerRoutes`](mcp-server/src/main/kotlin/luna/mcp/server/McpServerRoutes.kt), [`DiscordWebhookBroadcaster`](mcp-server/src/main/kotlin/luna/mcp/server/DiscordWebhookBroadcaster.kt), [`DiscordMessageChunker`](mcp-server/src/main/kotlin/luna/mcp/server/DiscordMessageChunker.kt), [`McpOAuthProvider`](mcp-server/src/main/kotlin/luna/mcp/server/McpOAuthProvider.kt)).

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for this repository. See `docs/agents/issue-tracker.md`.

### Triage labels

Canonical triage label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repository layout (`GLOSSARY.md` and `docs/adr/`). See `docs/agents/domain.md`.

### Coding standards

Judgement calls for daemon state bounds, transient error eviction, library boundaries, negative boundary coverage, and observable fallback degraded logs. See `CODING_STANDARDS.md`. Mechanical rules (daemon state collections, assertion styles, test naming, Dockerfile subproject parity, and sensitive key synchronization) are checked deterministically by [`CodingStandardsTest`](src/test/kotlin/luna/core/CodingStandardsTest.kt).
Unified environment configuration architecture and navigation pointers between `:root` and `:mcp-server` are documented in [`docs/architecture/configuration.md`](docs/architecture/configuration.md).

### Library & dependency exploration

Inspect library APIs and dependencies directly via their `build.gradle.kts` declarations and source/interface definitions rather than running shell classpath extraction scripts.

## Git & Conflict Resolution

- **Targeted Conflict Localization**: When integrating parallel branches or resolving merge conflicts, never guess line numbers or page through file chunks with `view_file`. Immediately run:
  ```bash
  git diff --name-only --diff-filter=U
  grep -n -E "^(<<<<<<<|=======|>>>>>>>)" <conflicted-file>
  ```
  to locate exact conflict markers and apply targeted replacements.

## Verification

- **Inner development & remediation loop (fast targeted tests)**: While iterating on code or fixing code review findings, run only narrow targeted tests to avoid monorepo build overhead and token waste. Never run full monorepo test suites or `docker build` on intermediate edits:
  ```bash
  ./gradlew test --tests "luna.summarizer.*"
  ./gradlew test --tests "luna.summarizer.ConversationSliceCollectorTest"
  ./gradlew test --tests "luna.summarizer.GeminiSummarizerTest"
  ./gradlew test --tests "luna.summarizer.SummaryServiceTest"
  ./gradlew test --tests "luna.summarizer.SummaryServiceWebhookTest"
  ./gradlew test --tests "luna.core.CodingStandardsTest"
  ./gradlew :mcp-server:test
  ./gradlew :libs:socialpeek:test
  ```
- **Pre-Review / Pre-commit check (full suite & formatting)**: Run `./gradlew lintKotlin test` (or `./gradlew check`) across all modules only after completing an implementation slice or addressing all review findings, before dispatching the Review Gate or creating a PR. Pre-commit hooks in `.githooks/` run this automatically.
- **CI parity (Docker build)**: Run `docker build -t luna:ci-test .` strictly as the final pre-PR/pre-merge verification step to ensure parity with CI (`.github/workflows/ci.yml`). Banned from inner-loop iteration.
