# Pluggable MCP Server and Personal Ledger Plugin

Status: accepted

## Context
ADR 0007 and ADR 0008 established `:mcp-server` as a decoupled, multi-tenant OAuth 2.1 Model Context Protocol (MCP) service on port `8081` with zero Kord/Discord Gateway dependencies, currently hardcoding Discord webhook tools (`send_discord_message`, `list_targets`) inside `McpProtocolHandler`. Adding a personal bookkeeping (Ledger) capability for Google Gemini Apps should remain decoupled from the Discord bot (`LunaPlugin` requires `Kord` and couples uptime to the Discord Gateway) and avoid adding a third container on low-RAM hosts, while keeping financial history safe from MCP client revocation and cleanly decoupled for a future REST API (e.g., an Android client).

## Decision
1. **MCP Server Plugin Seam & `:libs:ledger` Module**:
   - Introduce a lightweight `MCP Server Plugin` seam in `:mcp-server` that registers MCP tools (and optional Ktor `Route` hooks for future HTTP/REST endpoints) with `McpProtocolHandler` and `McpServerRoutes`, keeping `McpServerRoutes.kt` well below the 600-line guardrail.
   - Migrate the existing `send_discord_message` and `list_targets` tools into a built-in `DiscordWebhookPlugin` using the same seam.
   - Place bookkeeping domain models, service logic, and SQLite persistence in a dedicated, framework-independent library module `:libs:ledger` (zero Ktor and zero Kord dependencies).
   - Gate the Ledger plugin behind `MCP_LEDGER_ENABLED` (default `false`, opt-in via `:libs:config` `EnvReader`).

2. **Isolated `Ledger Store` (`ledger.db`)**:
   - Persist `Ledger Entry` records in a dedicated SQLite WAL database (`./.data/ledger.db`, configurable via `MCP_LEDGER_DB_PATH`, with `PRAGMA journal_mode=WAL;` and `PRAGMA busy_timeout=5000;`) under the existing `./.data:/app/.data` volume mount.
   - Separating `ledger.db` from `luna.db` enables independent backup/restore of personal financial history and eliminates write-lock contention with the Luna Discord bot container.

3. **Durable Owner Identity & Optional Allowlist**:
   - Resolve authenticated requests' `client_id` to durable `owner_id` (`mcp_clients.user_id`) via `McpClientRepository`.
   - Key `Ledger Entry` records strictly by `owner_id` with **no** foreign-key or application-level cascade delete on `client_id`, so `/mcp client revoke` and re-creation never destroy or orphan financial history.
   - Support an optional `MCP_LEDGER_ALLOWED_USER_IDS` allowlist (empty = all authenticated MCP users may access their own isolated ledger; non-empty = `ledger_*` tools are hidden from `tools/list` and rejected on `tools/call` for unlisted users).

4. **Single-Entry Domain Model, Monetary Precision, and Timezone**:
   - Model each `Ledger Entry` as a single-entry `INCOME` or `EXPENSE` record with `amount_minor: Long`, ISO-4217 `currency` (default `MCP_LEDGER_DEFAULT_CURRENCY=TWD`), free-form normalized `category` (trimmed/lowercase, guided by suggested defaults in tool descriptions rather than a rigid managed table), optional `note` and `payment_method` tag, and UTC epoch-millisecond timestamps (`occurred_at`, `created_at`, `updated_at`, `deleted_at`).
   - Accept **major units** at the MCP tool boundary (e.g., `150` or `"12.50"`) and convert to integer minor units via the currency's ISO-4217 decimal exponent (with `TWD` explicitly overridden to `0` decimal places to match everyday whole-dollar bookkeeping) to prevent LLM 100x scaling errors. Summaries group totals by currency without exchange-rate conversion.
   - Interpret calendar date boundaries (e.g., `2026-11-01`..`2026-11-30`) and format human-readable timestamps in `MCP_LEDGER_TIMEZONE` (default `Asia/Taipei`).

5. **MCP Tool Surface & Soft-Delete Safety**:
   - Expose six tools: `ledger_add_entry`, `ledger_list_entries`, `ledger_get_summary`, `ledger_update_entry`, `ledger_delete_entry`, and `ledger_list_categories`.
   - `ledger_delete_entry` performs a soft delete (`deleted_at`) rather than a physical row deletion so accidental LLM deletions remain recoverable.

## Considered Options & Deferred Scope
- **Hosting in `LunaPlugin` (`:app`) or a third container**: Rejected because `LunaPlugin` couples to Kord/Discord Gateway uptime, and a third container adds unnecessary JVM memory overhead.
- **Sharing `luna.db`**: Rejected to keep personal financial backups independent and avoid lock contention with Discord bot traffic.
- **Multi-account balances, transfers, budgets, and managed category tables**: Deferred to keep v1 low-friction for natural-language Gemini bookkeeping.
- **Android REST API (`/api/v1/ledger/...`), dedicated API key (`MCP_LEDGER_API_KEY`), and offline sync**: Explicitly deferred out of this phase; `:libs:ledger` and `MCP Server Plugin`'s optional route hook preserve the seam for adding them later without rewriting domain logic.
