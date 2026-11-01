# Agent Instructions

## Monorepo modules

- `src/main/kotlin/luna/`: Discord bot daemon, including host orchestrator (`plugin/`, `core/`), first-party plugins:
  - `summarizer/`: Multi-modal AI summarization pipeline ([`SummaryService`](src/main/kotlin/luna/summarizer/SummaryService.kt), [`GeminiSummarizer`](src/main/kotlin/luna/summarizer/GeminiSummarizer.kt), [`GeminiPromptBuilder`](src/main/kotlin/luna/summarizer/GeminiPromptBuilder.kt), [`ChatImageResolver`](src/main/kotlin/luna/summarizer/ChatImageResolver.kt), [`DialogueNoiseFilter`](src/main/kotlin/luna/summarizer/DialogueNoiseFilter.kt)).
  - `curtly/`, `preview/`, `undercover/`: First-party feature plugins.
- `libs/socialpeek/`: Multi-platform social media post and video metadata resolver library.

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for this repository. See `docs/agents/issue-tracker.md`.

### Triage labels

Canonical triage label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repository layout (`GLOSSARY.md` and `docs/adr/`). See `docs/agents/domain.md`.

### Coding standards

Judgement calls for daemon state bounds, transient error eviction, and library boundaries. See `CODING_STANDARDS.md`.

## Verification

- **Inner development loop (fast targeted tests)**: Run targeted test suites while iterating to avoid monorepo build overhead:
  ```bash
  ./gradlew test --tests "luna.summarizer.*"
  ./gradlew test --tests "luna.summarizer.SummaryServiceTest"
  ```
- **Pre-commit / Pre-PR check**: Run `./gradlew lintKotlin test` (or `./gradlew check`) to verify code style and test suite across all modules. The repo uses `org.jmailen.kotlinter` (`lintKotlin`).
- **CI parity**: Before pushing or marking a PR ready, also run `docker build -t luna:ci-test .` to ensure full parity with CI (`.github/workflows/ci.yml`).
