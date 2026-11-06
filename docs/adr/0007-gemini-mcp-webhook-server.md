# Decoupled Gemini MCP Server and Channel Webhook Broadcast

## Context
Operators and users of Google Gemini Apps (`gemini.google.com`) need a way to dispatch AI-generated summaries and notifications into Discord channels via custom Model Context Protocol (MCP) connections. However, running an MCP server inside the main Luna Discord bot process would tightly couple MCP server availability to Discord Gateway session state, expose Discord Bot tokens to unnecessary attack surfaces, and create deployment friction for reverse-proxying HTTPS traffic.

## Decision
1. **Decoupled Standalone Service (`:mcp-server`)**:
   - The MCP server is built as an independent Kotlin/Ktor service with zero compile-time or runtime dependencies on Kord, Discord Gateway, or Discord bot tokens.
   - It runs on its own configurable HTTP port (default `8081`), allowing it to remain online even during bot restarts or maintenance.

2. **Shared Data Contract via SQLite**:
   - The Discord bot and MCP server communicate asynchronously through a shared SQLite table `mcp_webhooks`.
   - Luna Bot manages webhook registrations via the `/mcp-webhook` slash command (enforcing `ManageGuild` permission).
   - The MCP server performs read-only queries against this table when broadcasting messages.

3. **Pure Discord Incoming Webhook Execution**:
   - Outbound messages are delivered strictly via HTTP POST to Discord's Incoming Webhook REST API (`https://discord.com/api/webhooks/...`), masquerading with Gemini branding.
   - Messages exceeding Discord's 2,000-character limit are automatically chunked along paragraph and newline boundaries into sequential posts.

4. **Embedded OAuth 2.1 Authorization Provider**:
   - The MCP server provides self-contained RFC 8414 metadata discovery (`/.well-known/oauth-authorization-server`) and RFC 9728 discovery (`/.well-known/oauth-protected-resource`).
   - Authorization code exchange with PKCE (`S256`) and Bearer token issuance are handled directly against operator-configured client credentials (`MCP_OAUTH_CLIENT_ID` and `MCP_OAUTH_CLIENT_SECRET`), eliminating third-party identity service dependencies.
   - Google official redirect URIs (`https://oauth-redirect.googleusercontent.com/r/*`) are explicitly whitelisted.
