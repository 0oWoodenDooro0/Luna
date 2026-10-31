# Agent Instructions

## Monorepo modules

- `src/main/kotlin/luna/`: Discord bot daemon, including event listeners (`listener/`), AI summarization pipeline (`summarizer/`), and HTTP dashboard (`web/`).
- `libs/socialpeek/`: Multi-platform social media post and video metadata resolver library.
- `libs/curtly/`: Lightweight URL shortening and analytics engine.

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for this repository. See `docs/agents/issue-tracker.md`.

### Triage labels

Canonical triage label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repository layout (`GLOSSARY.md` and `docs/adr/`). See `docs/agents/domain.md`.

### Coding standards

Judgement calls for daemon state bounds, transient error eviction, and library boundaries. See `CODING_STANDARDS.md`.
