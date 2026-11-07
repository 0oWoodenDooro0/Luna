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

### 1. Shared Configuration Library (`:libs:config`)
- **Package**: `luna.config`
- **Core Components**:
  - [`DotenvParser`](file:///home/user/IdeaProjects/Luna/libs/config/src/main/kotlin/luna/config/DotenvParser.kt): Pure parser supporting comments, `export ` prefixes, and quoted strings.
  - [`SecretMasker`](file:///home/user/IdeaProjects/Luna/libs/config/src/main/kotlin/luna/config/SecretMasker.kt): Single authoritative detection and masking of sensitive keys (`DISCORD_TOKEN`, `GEMINI_API_KEY`, `*_SECRET`, etc.).
  - [`PlaceholderResolver`](file:///home/user/IdeaProjects/Luna/libs/config/src/main/kotlin/luna/config/PlaceholderResolver.kt): Evaluates `${VAR:default}` syntax within configuration strings.
  - [`EnvSchemaAuditor`](file:///home/user/IdeaProjects/Luna/libs/config/src/main/kotlin/luna/config/EnvSchema.kt): Canonical `.env.example` schema parser, drift auditor, and alias resolver.
  - [`ConfigFinder`](file:///home/user/IdeaProjects/Luna/libs/config/src/main/kotlin/luna/config/ConfigFinder.kt): Resilient multi-tier `.env` file discovery across root and subprojects.
- **Responsibilities**:
  - Provides a single, deep, framework-agnostic configuration seam with zero Discord or Ktor dependencies, satisfying [ADR 0007](file:///home/user/IdeaProjects/Luna/docs/adr/0007-gemini-mcp-webhook-server.md).
  - Bundles and packages `.env.example` via Gradle `processResources`.

### 2. Luna Bot Host (`:root`)
- **Config Definition**: [`LunaCoreConfig`](file:///home/user/IdeaProjects/Luna/src/main/kotlin/luna/core/LunaConfig.kt)
- **Config Loader**: [`ConfigLoader`](file:///home/user/IdeaProjects/Luna/src/main/kotlin/luna/core/LunaConfig.kt)
- **Responsibilities**:
  - Resolves required credentials (`DISCORD_TOKEN`, `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`) and fails fast on blank or invalid values (`ConfigValidationException`).
  - Validates `PORT` in range `1..65535` (default: `8080`).
  - Delegates low-level `.env` parsing, secret masking, and schema drift auditing to `luna.config`.
  - Injects parsed configurations into `PluginContext` for decoupled plugin consumption.

### 3. Standalone MCP Server (`:mcp-server`)
- **Config Definition**: [`McpServerConfig`](file:///home/user/IdeaProjects/Luna/mcp-server/src/main/kotlin/luna/mcp/server/McpServerConfig.kt)
- **Responsibilities**:
  - Operates as an independent microservice decoupled from Discord Gateway/tokens per [ADR 0007](file:///home/user/IdeaProjects/Luna/docs/adr/0007-gemini-mcp-webhook-server.md).
  - Delegates `.env` parsing, port validation, secret masking, and section 5 schema drift audit to `luna.config`.
  - Eliminates cross-module code duplication and fragile regex parity guardrails.

### 4. Plugin Context Access (`PluginContext`)
- **Interface**: [`PluginContext`](file:///home/user/IdeaProjects/Luna/src/main/kotlin/luna/plugin/PluginContext.kt)
- **Responsibilities**:
  - Provides typed configuration retrieval with defaults: `context.config("key", defaultValue)`.
  - Supports dual-namespace lookup (e.g. `summarizer.emoji` automatically falls back to `SUMMARY_EMOJI`).
  - Eliminates direct `System.getenv(...)` calls across all feature plugins (`CurtlyPlugin`, `SocialPreviewPlugin`, `SummarizerPlugin`).

## Guardrails & Verification

1. **Single Authoritative Seam**: Shared configuration logic resides in `:libs:config`, verified directly through unit tests rather than cross-file source scraping.
2. **Worktree Resilience**: Directory tree walkers in guardrail tests prune dotfiles (`.worktrees`, `.git`) and `build` directories at traversal entry (`.onEnter { !it.name.startsWith(".") && it.name != "build" }`).
