# Agent Instructions

## Monorepo modules

- `src/main/kotlin/luna/`: Discord bot daemon, including host orchestrator (`plugin/`, `core/`), first-party plugins (`curtly/`, `preview/`, `summarizer/`, `undercover/`).
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

Run `./gradlew lintKotlin test` (or `./gradlew check`) to verify code style and test suite. The repo uses `org.jmailen.kotlinter` (`lintKotlin`).
