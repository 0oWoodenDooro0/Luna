# Unified Environment Configuration Architecture

## Overview

Luna and its decoupled microservices follow a unified, layered configuration contract designed for containerized deployments, local development, and security auditing.

```
Configuration Resolution Hierarchy
┌───────────────────────────────────────────────┐
│              application.yml                  │ (Highest precedence, supports ${VAR:default})
├───────────────────────────────────────────────┤
│            System.getenv()                    │ (Container & host OS environment variables)
├───────────────────────────────────────────────┤
│              .env file                        │ (Local development environment variables)
└───────────────────────────────────────────────┘
```

## Core Modules & Navigation Pointers

### 1. Luna Bot Host (`:root`)
- **Config Definition**: [`LunaCoreConfig`](file:///home/user/IdeaProjects/Luna/src/main/kotlin/luna/core/LunaConfig.kt)
- **Config Loader**: [`ConfigLoader`](file:///home/user/IdeaProjects/Luna/src/main/kotlin/luna/core/LunaConfig.kt)
- **Responsibilities**:
  - Resolves required credentials (`DISCORD_TOKEN`, `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`) and fails fast on blank or invalid values (`ConfigValidationException`).
  - Validates `PORT` in range `1..65535` (default: `8080`).
  - Masks sensitive secrets (`DISCORD_TOKEN`, `DISCORD_CLIENT_SECRET`, `GEMINI_API_KEY`, etc.) as `******` before emitting structured JSON startup audit logs (`JsonLogger`).
  - Injects parsed configurations into `PluginContext` for decoupled plugin consumption.

### 2. Standalone MCP Server (`:mcp-server`)
- **Config Definition**: [`McpServerConfig`](file:///home/user/IdeaProjects/Luna/mcp-server/src/main/kotlin/luna/mcp/server/McpServerConfig.kt)
- **Navigation Pointer**: See cross-reference in [`LunaConfig.kt`](file:///home/user/IdeaProjects/Luna/src/main/kotlin/luna/core/LunaConfig.kt).
- **Responsibilities**:
  - Operates as an independent microservice decoupled from Discord Gateway/tokens per [ADR 0007](file:///home/user/IdeaProjects/Luna/docs/adr/0007-gemini-mcp-webhook-server.md).
  - Automatically probes the project root `.env` (`findDefaultEnvFile`) when running outside Docker.
  - Shares identical dotenv parsing (`parseDotenv`), port validation range (`1..65535`), and secret masking semantics with the bot host.
  - Guards against drift via automated test [`CodingStandardsTest`](file:///home/user/IdeaProjects/Luna/src/test/kotlin/luna/core/CodingStandardsTest.kt).

### 3. Plugin Context Access (`PluginContext`)
- **Interface**: [`PluginContext`](file:///home/user/IdeaProjects/Luna/src/main/kotlin/luna/plugin/PluginContext.kt)
- **Responsibilities**:
  - Provides typed configuration retrieval with defaults: `context.config("key", defaultValue)`.
  - Supports dual-namespace lookup (e.g. `summarizer.emoji` automatically falls back to `SUMMARY_EMOJI`).
  - Eliminates direct `System.getenv(...)` calls across all feature plugins (`CurtlyPlugin`, `SocialPreviewPlugin`, `SummarizerPlugin`).

## Guardrails & Verification

1. **Deterministic Parity Checks**: `CodingStandardsTest` automatically asserts that sensitive key masking definitions between `LunaConfig` and `McpServerConfig` stay strictly identical.
2. **Worktree Resilience**: Directory tree walkers in guardrail tests prune dotfiles (`.worktrees`, `.git`) and `build` directories at traversal entry (`.onEnter { !it.name.startsWith(".") && it.name != "build" }`).
