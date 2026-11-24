# Agent Instructions

## Reference Pointers

- **Coding standards**: Read [`CODING_STANDARDS.md`](CODING_STANDARDS.md) when modifying or reviewing services, shared libraries, or tests. Mechanical rules in [`gradle/coding-standards.gradle.kts`](gradle/coding-standards.gradle.kts) run automatically via `.githooks/pre-commit`; invoke `./gradlew lintCodingStandards --console=plain -q` manually only when debugging a rule failure outside a commit (and `./gradlew tightenCodingStandardsBaselines --console=plain -q` to auto-tighten Rule 10 ratchet baselines).
- **Domain & ADRs**: Read [`GLOSSARY.md`](GLOSSARY.md) and relevant [`docs/adr/`](docs/adr/) before naming or designing domain changes ([`docs/agents/domain.md`](docs/agents/domain.md)).
- **Issues & triage**: GitHub Issues workflow -> [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md); triage labels -> [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md).
- **Configuration**: Unified env architecture -> [`docs/architecture/configuration.md`](docs/architecture/configuration.md).
- **AI Trace debugging**: Daily JSONL trace schema & `jq`/`rg` queries -> [`docs/architecture/ai-tracing.md`](docs/architecture/ai-tracing.md).
- **Kord & libraries**: `0.19.0-SNAPSHOT` constraints & API recipes -> [`docs/kord-recipes.md`](docs/kord-recipes.md); query `context7` MCP first for other library docs (Ktor, Exposed, kotlinx).

## Verification

- **Inner loop**: Run targeted tests with `--console=plain -q` (e.g. `./gradlew test --tests "luna.summarizer.*" --console=plain -q`; failed test names and exceptions still print under `-q`).
- **Pre-review / pre-PR**: Run `./gradlew formatKotlin check --console=plain -q` across all modules (`.githooks/pre-commit` runs `lintKotlin` + `lintCodingStandards`). When pushing immediately, `.githooks/pre-push` already runs `./gradlew check`—run `./gradlew formatKotlin` (confirm clean diff) and CI-parity Docker build, then `git push` without duplicating `./gradlew check` on the same commit.
- **CI parity**: Run `docker build -t luna:ci-test .` strictly as the final pre-PR/pre-merge check.
