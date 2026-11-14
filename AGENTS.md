# Agent Instructions

## Modules & Navigation

- `src/main/kotlin/luna/`: Discord bot host (`plugin/`), shared `core/` & `core/ai/`, and plugins (`chatter/`, `summarizer/`, `preview/`, `curtly/`, `mcp/`, `undercover/`). Explore symbols with `rg -n` instead of reading whole files.
- `libs/socialpeek/`: Social post and video metadata resolver library.
- `libs/config/`: Shared dotenv/env parsing, secret masking, and schema validation.
- `libs/mcp-data/`: Shared SQLite persistence for MCP client credentials and webhooks.
- `mcp-server/`: Standalone Gemini MCP server decoupled from Discord bot runtime.
- `src/test/kotlin/` (`*Test.kt`): Split new test surfaces into focused files by concern rather than growing existing suites past 600 lines.

## Reference Pointers

- **Issues & specs**: GitHub Issues workflow -> [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md); triage labels -> [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md).
- **Domain & ADRs**: Glossary and architecture decisions -> [`docs/agents/domain.md`](docs/agents/domain.md).
- **Coding standards**: Judgement calls -> [`CODING_STANDARDS.md`](CODING_STANDARDS.md) (for review, not inner-loop reads); mechanical rules (bounded caches, assertion shapes, JVM-safe test names, test suite size cap, Dockerfile parity, `.env.example` parity, cross-plugin isolation) -> run [`CodingStandardsTest`](src/test/kotlin/luna/core/CodingStandardsTest.kt) rather than reading it end-to-end.
- **Configuration**: Unified env architecture -> [`docs/architecture/configuration.md`](docs/architecture/configuration.md).
- **AI Trace debugging**: Daily JSONL trace schema & `jq`/`rg` queries -> [`docs/architecture/ai-tracing.md`](docs/architecture/ai-tracing.md).
- **Dependencies & Kord**: Inspect `build.gradle.kts`, source definitions, or [`docs/kord-recipes.md`](docs/kord-recipes.md) instead of decompiling JARs.

## Verification

- **Inner loop**: Run only targeted tests with `--console=plain -q` (e.g. `./gradlew test --tests "luna.summarizer.*" --console=plain -q`); locate symbols with `rg -n` and bounded `view_file` ranges.
- **Pre-review / pre-PR**: Run `./gradlew formatKotlin && ./gradlew check --console=plain -q` across all modules (`.githooks/pre-commit` runs `lintKotlin` + `CodingStandardsTest`; `.githooks/pre-push` runs `check`).
- **CI parity**: Run `docker build -t luna:ci-test .` strictly as the final pre-PR/pre-merge check.
