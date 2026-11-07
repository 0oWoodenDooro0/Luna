# Multi-Tenant Gemini MCP Server with Self-Service Discord Client and Tagged Webhooks

## Context
ADR 0007 established a decoupled, standalone Model Context Protocol (MCP) server communicating asynchronously with the Luna Discord bot through a shared SQLite database. However, ADR 0007 relied on a single operator-configured static OAuth client (`MCP_OAUTH_CLIENT_ID` and `MCP_OAUTH_CLIENT_SECRET`) and global broadcast across all registered webhooks. This architecture prevented individual Discord server members from independently connecting their own Google Gemini Apps to trigger personalized notifications or send messages to specific Discord channels without exposing server-wide credentials or broadcasting across all server channels.

## Decision
1. **Multi-Tenant Client Identity & Quota**:
   - Each Discord user is strictly allocated **one personal Client ID** (`luna_cli_...`) via the `/mcp client create` Discord slash command.
   - Client secrets are generated with 32 URL-safe random bytes, hashed using SHA-256 with a 16-byte random salt, and stored in `mcp_clients` (`client_id`, `user_id` UNIQUE, `client_name`, `secret_hash`, `secret_salt`, `created_at`, `updated_at`). The plain secret is shown ephemerally to the user once upon creation or rotation.
   - Each client can bind up to **10 Discord channel webhooks** tagged with mandatory, unique labels (`tag`).

2. **Self-Service Discord Slash Command Hierarchy (`/mcp`)**:
   - All client and webhook administration commands reside under the root `/mcp` command, eliminating the legacy `/mcp-webhook` command:
     - `/mcp client create [name]`: Generates client ID and secret with ephemeral Gemini setup guidance.
     - `/mcp client show`: Ephemerally displays client ID, creation time, and quota status (`x/10`).
     - `/mcp client reset-secret`: Rotates client secret and invalidates existing OAuth tokens.
     - `/mcp client revoke`: Permanently deletes client credentials, cascades webhook deletions, and revokes tokens.
     - `/mcp webhook add tag:<tag> [url:<url>]`: Binds a channel webhook with mandatory tag validation (1-32 characters, no whitespace). If `url` is omitted, validates `Manage Webhooks` channel permission and auto-provisions a Discord webhook via Kord REST API.
     - `/mcp webhook list`: Ephemerally lists active tags, channels, and quota.
     - `/mcp webhook remove tag:<tag>`: Removes webhook by tag and deletes the Discord channel webhook via REST API if auto-provisioned.

3. **Multi-Process SQLite Concurrency & Invalidation**:
   - Both the Discord bot daemon (`:app`) and MCP service (`:mcp-server`) configure SQLite connections with `PRAGMA journal_mode=WAL;` and `PRAGMA busy_timeout=5000;`, enabling concurrent reads and writes without database lock contention.
   - When a client secret is rotated (`reset-secret`) or revoked (`revoke`), `:app` updates `updated_at` or deletes the row in `mcp_clients`.
   - `:mcp-server` verifies that an in-memory `AccessToken` satisfies `issuedAt >= client.updatedAt` on every request. If the client was revoked or secret rotated, the token is instantly invalidated without requiring cross-process IPC.

4. **Multi-Tenant OAuth 2.1 & Zero-Friction Authorization**:
   - The `/oauth/authorize` endpoint dynamically validates `client_id` against `mcp_clients` and verifies that `redirect_uri` matches Google's redirect URI regex whitelist (`https://oauth-redirect.googleusercontent.com/r/*`), issuing an authorization code and redirecting immediately without browser login friction.
   - The `/oauth/token` endpoint validates incoming client secrets against the salted SHA-256 hash using constant-time comparison, enforces PKCE (`S256`), and issues access tokens tagged with the authenticated `client_id`.
   - Client identity is resolved strictly from validated Bearer tokens or authenticated SSE sessions; unauthenticated query parameters or headers are prohibited from overriding client identity.

5. **Tagged MCP Tool Dispatch & Feedback**:
   - The MCP tool `send_discord_message` strictly requires `content` (string) and `target` (string).
   - If `target == "all"`, messages are fanned out to all webhooks owned by the authenticated `client_id`.
   - If `target` is a specific tag, messages are routed strictly to the matching webhook for that client. Cross-tenant dispatch is physically prevented.
   - A companion tool `list_targets` is provided for Gemini to inspect available tags and quota status (`x/10`).
   - If no webhooks are registered or the target tag is missing, actionable guidance in Traditional Chinese is returned to guide the user to `/mcp webhook add`.
