# Agent Instructions

## Reference Pointers

- **Coding standards**: Read [`CODING_STANDARDS.md`](CODING_STANDARDS.md) when modifying or reviewing services, shared libraries, or tests. Verify mechanical rules by running `./gradlew lintCodingStandards --console=plain -q`.
- **Domain & ADRs**: Read [`GLOSSARY.md`](GLOSSARY.md) and relevant [`docs/adr/`](docs/adr/) before naming or designing domain changes ([`docs/agents/domain.md`](docs/agents/domain.md)).
- **Issues & triage**: GitHub Issues workflow -> [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md); triage labels -> [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md).
- **Configuration**: Unified env architecture -> [`docs/architecture/configuration.md`](docs/architecture/configuration.md).
- **AI Trace debugging**: Daily JSONL trace schema & `jq`/`rg` queries -> [`docs/architecture/ai-tracing.md`](docs/architecture/ai-tracing.md).
- **Kord & libraries**: `0.19.0-SNAPSHOT` constraints & API recipes -> [`docs/kord-recipes.md`](docs/kord-recipes.md); query `context7` MCP first for other library docs (Ktor, Exposed, kotlinx).
- **Code style**: Keep new code and comments consistent with the surrounding codebase.

## Verification

- **Inner loop**: Run targeted tests with `--console=plain -q` (e.g. `./gradlew test --tests "luna.summarizer.*" --console=plain -q`; failed test names and exceptions still print under `-q`).
- **Pre-review / pre-PR**: Run `./gradlew formatKotlin && ./gradlew check --console=plain -q` across all modules (`.githooks/pre-commit` runs `lintKotlin` + `lintCodingStandards`; `.githooks/pre-push` runs `check`).
- **CI parity**: Run `docker build -t luna:ci-test .` strictly as the final pre-PR/pre-merge check.
