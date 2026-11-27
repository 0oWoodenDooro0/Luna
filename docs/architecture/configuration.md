# Unified Environment Configuration Architecture

## Overview

Luna and its decoupled microservices follow a unified, two-layer, strict fail-fast configuration contract (see [ADR 0018](../adr/0018-unified-strict-env-reader-configuration.md)):

```
Configuration Resolution Hierarchy
┌───────────────────────────────────────────────┐
│            System.getenv()                    │ (Highest precedence: container & host OS env)
├───────────────────────────────────────────────┤
│              .env file                        │ (Local development environment variables)
└───────────────────────────────────────────────┘
```

In unit tests, `EnvReader.of(mapOf(...))` provides an isolated in-memory reader with zero filesystem or OS environment access.

## Strict Fail-Fast Contract

- **Canonical Keys Only**: Every setting is read using its exact `SCREAMING_SNAKE_CASE` key declared in `.env.example`. Legacy dotted keys, camelCase aliases, `application.yml`, and `CORE_KNOWN_ALIASES` are not supported.
- **Blank-as-Unset**: Unset keys and blank/whitespace-only values (`""`, `"   "`) are treated as unset, resolving to the caller's default or `null` (or throwing `ConfigValidationException` for `requiredString`).
- **Fail-Fast Validation**: Malformed types (non-numeric `int`/`long`/`double`/`port`, booleans other than case-insensitive `true`/`false`, invalid Discord Snowflake IDs in CSV lists) and out-of-range numbers throw `ConfigValidationException` immediately at startup—never silently falling back or clamping via `coerceIn`.
- **Secret Masking**: `ConfigValidationException` messages and startup audit logs mask sensitive values (`DISCORD_TOKEN`, `GEMINI_API_KEY`, `*_SECRET`, etc.) via `SecretMasker`.

## Core Modules & Navigation Pointers

### 1. Shared Configuration Library (`:libs:config`)
- **Package**: `luna.config`
- **Core Components**:
  - [`EnvReader`](../../libs/config/src/main/kotlin/luna/config/EnvReader.kt): Single deep module unifying `.env` discovery, `System.getenv()` precedence, drift auditing (`EnvReader.load(...)`), in-memory test construction (`EnvReader.of(...)`), and strict typed readers (`string`, `requiredString`, `firstString`, `int`, `long`, `double`, `boolean`, `csv`, `port`).
  - [`ConfigValidationException`](../../libs/config/src/main/kotlin/luna/config/ConfigValidationException.kt): Shared validation exception thrown across `:root`, `:mcp-server`, and all plugins.
  - [`DotenvParser`](../../libs/config/src/main/kotlin/luna/config/DotenvParser.kt): Pure parser supporting comments, `export ` prefixes, and quoted strings.
  - [`SecretMasker`](../../libs/config/src/main/kotlin/luna/config/SecretMasker.kt): Authoritative detection and masking of sensitive keys.
  - [`EnvSchemaAuditor`](../../libs/config/src/main/kotlin/luna/config/EnvSchema.kt): Canonical `.env.example` schema parser and strict drift auditor.
  - [`ConfigFinder`](../../libs/config/src/main/kotlin/luna/config/ConfigFinder.kt): Multi-tier `.env` file discovery across root and subprojects.
- **Responsibilities**:
  - Provides a framework-agnostic configuration seam with zero Discord or Ktor dependencies, satisfying [ADR 0007](../adr/0007-gemini-mcp-webhook-server.md).
  - Bundles and packages `.env.example` via Gradle `processResources`.

### 2. Luna Bot Host (`:root`)
- **Config Definition**: [`LunaCoreConfig`](../../src/main/kotlin/luna/core/LunaConfig.kt)
- **Responsibilities**:
  - Loads via `EnvReader.load(...)`, validates required credentials (`DISCORD_TOKEN`, `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`) and `PORT` (`1..65535`, default `8080`), and throws `ConfigValidationException` on invalid values.
  - Injects the shared `EnvReader` into `PluginContext.env` for plugin consumption.
  - `PluginLoader` enables/disables each `LunaPlugin` solely via `PLUGINS_<NAME>_ENABLED` (default `true`).

### 3. Standalone MCP Server (`:mcp-server`)
- **Config Definition**: [`McpServerConfig`](../../mcp-server/src/main/kotlin/luna/mcp/server/McpServerConfig.kt)
- **Responsibilities**:
  - Operates as an independent microservice decoupled from Discord Gateway/tokens per [ADR 0007](../adr/0007-gemini-mcp-webhook-server.md).
  - Loads via `EnvReader.load(...)` and reads `MCP_PORT` (`1..65535`, default `8081`, never falling back to `PORT`), `MCP_DB_PATH`, `MCP_PUBLIC_BASE_URL`, `MCP_LEDGER_ENABLED` (default `false`), `MCP_LEDGER_DB_PATH` (default `./.data/ledger.db`), `MCP_LEDGER_DEFAULT_CURRENCY` (default `TWD`), `MCP_LEDGER_TIMEZONE` (default `Asia/Taipei`), and `MCP_LEDGER_ALLOWED_USER_IDS` (optional comma-separated user ID allowlist).

### 4. Plugin Context Access (`PluginContext`)
- **Interface**: [`PluginContext`](../../src/main/kotlin/luna/plugin/PluginContext.kt)
- **Responsibilities**:
  - Exposes `val env: EnvReader` as the sole configuration boundary for plugins.
  - Explicit cross-key business fallbacks (such as `CHATTER_GEMINI_MODEL` falling back to `GEMINI_MODEL`) are expressed directly with `env.firstString(...)`.
  - Strict opt-in settings such as `CHATTER_EMOJI_VISION_MODEL`, `CHATTER_DECISION_MODEL`, `CHATTER_MEMORY_DISTILL_MODEL`, and `CHATTER_MEMORY_EMBEDDING_MODEL` read only their own key and remain `null` when unset or blank.

## Guardrails & Verification

1. **Build-Time `.env.example` Parity**: Gradle `lintCodingStandards` Rule 5 (`env-example-parity`) statically extracts keys from `EnvReader` calls (including every key argument of `firstString`), `System.getenv(...)`, and `LunaPlugin.name` (`PLUGINS_<NAME>_ENABLED`) and verifies bidirectional parity with `.env.example`.
2. **Worktree Resilience**: Directory tree walkers in guardrail tests prune dotfiles (`.worktrees`, `.git`) and `build` directories at traversal entry (`.onEnter { !it.name.startsWith(".") && it.name != "build" }`).
