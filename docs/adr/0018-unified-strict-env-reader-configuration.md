# Unified Strict EnvReader Configuration Architecture

Status: accepted

## Context

`Luna Host`, `Decoupled MCP Server`, and individual `Luna Plugin` modules currently resolve environment configuration through several overlapping layers:

1. **Redundant dual-namespace key resolution**: `DefaultPluginContext` expands every lookup key into up to six candidate aliases (dotted camelCase, dotted snake_case, `SCREAMING_SNAKE_CASE`, `summarizer`↔`summary` namespace swaps, `PLUGINS_<NAME>_<PROP>`, and bare property names with collision guards), yet feature plugins (`CurtlyPlugin`, `SocialPreviewPlugin`, `SummarizerPlugin`, `ChatterPlugin`) still hand-write 3–6 fallback chains per property.
2. **Nearly empty YAML layer**: `src/main/resources/application.yml` contains only two lines (`curtly.baseUrl: "${BASE_URL:http://localhost:8080}"`), yet keeps `snakeyaml`, recursive `flattenYaml`, `PlaceholderResolver` (`${VAR:default}`), and a three-tier precedence hierarchy alive.
3. **Duplicated loading and type-conversion paths**: `LunaConfig` and `McpServerConfig` duplicate `.env` discovery, `System.getenv()` merging, and drift auditing; `ChatterConfig`, `ChatterDecisionConfig`, and `ChatterMemoryConfig` maintain separate `fromPluginContext` and `fromEnv` entrypoints; plugins repeatedly chain `Boolean`/`Int`/`Long`/`String` casts and ad-hoc `.coerceIn(...)` clamping.
4. **Silent misconfiguration**: Plugins silently fall back to defaults or clamp out-of-range numbers on invalid environment values, masking operator typos at startup, while `EnvSchemaAuditor` (`CORE_KNOWN_ALIASES`) and Gradle Rule 5 (`env-example-parity`) require manual cross-file synchronization across six separate regexes.

## Decision

1. **Two-Layer Configuration Hierarchy (Remove `application.yml`)**:
   - Simplify configuration precedence strictly to **`System.getenv()` > `.env`** (with optional in-memory overrides for tests).
   - Remove `src/main/resources/application.yml`, the `snakeyaml` dependency, `flattenYaml`, and `PlaceholderResolver`.

2. **Single Canonical `SCREAMING_SNAKE_CASE` Keys (Zero Legacy Aliases)**:
   - Standardize all configuration keys across `Luna Host`, `Decoupled MCP Server`, and every `Luna Plugin` on the exact `SCREAMING_SNAKE_CASE` keys documented in `.env.example`.
   - Remove all dotted, camelCase, prefixed (`PLUGINS_<NAME>_<PROP>`), and legacy alias lookups (such as `DISCORD_APP_ID`, `curtly.baseUrl`, `summary.*`, `social_preview.youtubeApiKey`) as well as `EnvSchemaAuditor.CORE_KNOWN_ALIASES`.
   - Express intentional cross-key business fallbacks explicitly at the call site via `firstString(...)` (for example, `CHATTER_GEMINI_MODEL` falling back to `GEMINI_MODEL`), while keeping strict opt-in settings (such as `CHATTER_EMOJI_VISION_MODEL`, `CHATTER_DECISION_MODEL`, `CHATTER_MEMORY_DISTILL_MODEL`, `CHATTER_MEMORY_EMBEDDING_MODEL`) single-key with no fallback.
   - Scope `McpServerConfig` port resolution strictly to `MCP_PORT` (default `8081`), removing fallback to `PORT` and legacy `port`/`mcp.port` aliases.

3. **Single Deep Configuration Seam in `:libs:config` (`EnvReader` + `ConfigValidationException`)**:
   - Introduce `EnvReader` in the `:libs:config` `Shared Library` (zero Discord or Ktor dependencies, preserving [ADR 0007](0007-gemini-mcp-webhook-server.md)) unifying:
     - **Loading & auditing**: `EnvReader.load(...)` combining `ConfigFinder`, `DotenvParser`, `System.getenv()`, test overrides, and `EnvSchemaAuditor.auditDrift`.
     - **In-memory test construction**: `EnvReader.of(mapOf(...))`.
     - **Typed reading**: `string`, `requiredString`, `firstString`, `int`, `long`, `double`, `boolean`, `csv`, and `port` (with optional range bounds on numeric readers).
   - Promote `ConfigValidationException` into `luna.config.ConfigValidationException` and remove the duplicate `McpConfigValidationException`.

4. **Strict Fail-Fast Validation Everywhere**:
   - Treat unset keys and blank/whitespace-only strings (`""`, `"   "`) as unset (resolving to default or `null`, or throwing `ConfigValidationException` for `requiredString`).
   - Fail fast with `ConfigValidationException` on **any** invalid type (non-numeric integers/longs/doubles, booleans other than `true`/`false`, invalid Discord Snowflake IDs in CSV lists) or **any** out-of-range numeric value—eliminating silent fallback-to-default and silent `.coerceIn(...)` clamping across both core services and plugins.
   - Mask sensitive values via `SecretMasker` in all validation error messages while naming the offending key and expected type/range.

5. **Simplified `Plugin Context`, `PluginLoader`, and Plugin Configs**:
   - Remove `PluginContext.config(key, clazz)`, `PluginContext.config(key, defaultValue)`, their reified inline extensions, and `DefaultPluginContext`'s `resolveCandidateKeys` / `castOrConvert`. Expose a single `val env: EnvReader` on `Plugin Context`.
   - Simplify `PluginLoader` enable/disable resolution to the single canonical `PLUGINS_<NAME>_ENABLED` key (default `true`, matching `.env.example` Section 4), removing `PLUGINS_DISABLED` and all dotted keys.
   - Consolidate `ChatterConfig`, `ChatterDecisionConfig`, and `ChatterMemoryConfig` from dual `fromPluginContext` / `fromEnv` entrypoints into a single `from(env: EnvReader)`. Migrate `CurtlyPlugin`, `SocialPreviewPlugin`, `SummarizerPlugin`, `ChatterPlugin`, `McpPlugin`, `LunaCoreConfig`, and `McpServerConfig` to read directly through `EnvReader`.

6. **Streamlined Build-Time Parity Guardrail & Docs**:
   - Update Gradle `lintCodingStandards` Rule 5 (`env-example-parity`) to scan `EnvReader` calls (including all key arguments of `firstString(...)`), `System.getenv(...)`, and `LunaPlugin.name` (`PLUGINS_<NAME>_ENABLED`), removing legacy `context.config(...)`, `read*(lookup, ...)`, and `config[...]` regexes.
   - Update `docs/architecture/configuration.md` to document the two-layer hierarchy, `EnvReader` seam, and fail-fast contract.

## Consequences

- **Positive**: Eliminates multi-layer key aliasing, redundant type-conversion boilerplate, duplicated host/MCP loaders, and the `snakeyaml` dependency; makes every plugin's environment dependencies explicit and statically auditable; surfaces operator misconfigurations immediately at startup instead of silently clamping or ignoring them; simplifies testing across all modules to `EnvReader.of(mapOf(...))`.
- **Negative / Trade-offs**: Breaking change for any deployment still relying on legacy dotted keys, `application.yml`, `DISCORD_APP_ID`, `PLUGINS_DISABLED`, or `PORT` (instead of `MCP_PORT`) for `Decoupled MCP Server`; strict fail-fast means an out-of-range or malformed optional plugin env var now prevents startup until fixed.
