# Agent Instructions

## Reference Pointers

- **Coding standards**: Read [`CODING_STANDARDS.md`](CODING_STANDARDS.md) when modifying or reviewing services, shared libraries, or tests. Mechanical rules in [`gradle/coding-standards.gradle.kts`](gradle/coding-standards.gradle.kts) run via `.githooks/pre-commit` and `lintCodingStandards` (`./gradlew tightenCodingStandardsBaselines --console=plain -q` auto-tightens Rule 10 & Rule 12 ratchet baselines).
- **Domain & ADRs**: Read [`GLOSSARY.md`](GLOSSARY.md) and relevant [`docs/adr/`](docs/adr/) before naming or designing domain changes ([`docs/agents/domain.md`](docs/agents/domain.md)).
- **Issues & triage**: GitHub Issues workflow -> [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md); triage labels -> [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md).
- **Configuration**: Unified env architecture -> [`docs/architecture/configuration.md`](docs/architecture/configuration.md).
- **AI Trace debugging**: Daily JSONL trace schema & `jq`/`rg` queries -> [`docs/architecture/ai-tracing.md`](docs/architecture/ai-tracing.md).
- **Kord & libraries**: `0.19.0-SNAPSHOT` constraints & API recipes -> [`docs/kord-recipes.md`](docs/kord-recipes.md); query `context7` MCP first for other library docs (Ktor, Exposed, kotlinx).

## Verification

- **Inner loop**: Bundle formatting, `lintCodingStandards`, and targeted tests covering **every modified package** into one Gradle call (e.g. `./gradlew formatKotlin lintCodingStandards test --tests "luna.chatter.*" --tests "luna.core.ai.*" --console=plain -q`; failed test names and exceptions still print under `-q`).
- **Pre-review / pre-PR**: Run `code-review` and `./gradlew formatKotlin check --console=plain -q` across all modules before committing/pushing. When pushing immediately, `.githooks/pre-push` already runs `./gradlew check`—chain without duplicating it: `./gradlew formatKotlin --console=plain -q && [ -z "$(git status --porcelain)" ] && docker build -t luna:ci-test . && git push -u origin <branch>`.
- **CI parity**: Run `docker build -t luna:ci-test .` strictly as the final pre-PR/pre-merge check.
