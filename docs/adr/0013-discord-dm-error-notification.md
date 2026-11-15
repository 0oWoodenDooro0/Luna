# Discord DM Owner Error Notification via Logback Appender

Status: accepted

## Context

`Luna Host` emits runtime warnings and errors across three uncoordinated paths: `JsonLogger` (`"JSON_LOGGER"`), standard SLF4J `logger.warn`/`logger.error` calls inside services and plugins, and uncaught JVM/coroutine exceptions (with no global `Thread.setDefaultUncaughtExceptionHandler` registered). Operators previously had to inspect server log files (`data/logs/luna-json.log`) or `AI Trace` JSONL files manually to notice failures.

Meanwhile, `Decoupled MCP Server` (`:mcp-server`) runs as a separate process with zero Kord or Discord bot token dependencies per [ADR 0007](0007-gemini-mcp-webhook-server.md).

## Decision

1. **Scope to `Luna Host`**:
   - `Owner Error Notification` operates strictly inside `Luna Host`, covering core infrastructure, `luna.core.ai`, all `Luna Plugin`s, and shared library (`:libs:*`) failures surfaced in the host runtime.
   - `Decoupled MCP Server` is excluded to preserve ADR 0007's process and dependency decoupling.

2. **Central Logback Appender + Global Uncaught Exception Handler**:
   - Capture warning and error events through a custom non-blocking Logback Appender backed by a bounded coroutine channel/queue, paired with a global `Thread.setDefaultUncaughtExceptionHandler` that logs uncaught exceptions at `ERROR` level.
   - Avoid modifying `JsonLogger` signatures or scattering explicit notifier calls across plugin and service call sites, preserving cross-plugin isolation.

3. **Asymmetric Source & Severity Filtering**:
   - First-party loggers (`luna.*`, `dev.socialpeek.*`, `JSON_LOGGER`, and uncaught exceptions) notify on both `WARN` and `ERROR`, ensuring degraded fallback warnings remain visible.
   - Third-party loggers (`dev.kord.*`, `io.ktor.*`, `io.netty.*`, etc.) notify on `ERROR` only, suppressing routine framework reconnection and transport warnings.

4. **Fingerprint Deduplication, Separate Per-Level Rate Limits, and Startup Buffering**:
   - Deduplicate matching error fingerprints (and correlate paired `logger.error` / `JsonLogger.error` emissions for the same failure) within a configurable cooldown window (`ERROR_NOTIFY_COOLDOWN_SECONDS`, default `300`) using a bounded cache, attaching `suppressed` counts on subsequent delivery after cooldown expiry.
   - Enforce independent per-minute global caps for `ERROR` and `WARN` (default 5/min each) so high-frequency warnings cannot starve critical error notifications.
   - Retain pre-`ReadyEvent` startup events (such as `CONFIG_DRIFT` warnings or plugin initialization failures) in a bounded startup buffer (max 20 events) and flush under deduplication/rate-limit rules once Kord is ready.

5. **Configuration, Privacy Redaction, and Fail-Open Guardrails**:
   - Configure via `ERROR_NOTIFY_DISCORD_USER_IDS` (comma-separated Discord User IDs; blank/unset disables notifications), optional `ERROR_NOTIFY_ENV` instance label, and optional `ERROR_NOTIFY_COOLDOWN_SECONDS`, maintaining 1:1 `.env.example` parity.
   - Render each alert as a single structured Discord DM (`<= 2,000` characters) using `:libs:config` secret masking, non-sensitive context IDs (omitting full chat transcripts), and fail-open delivery with a recursion guard so notifier failures never re-enter the notification pipeline.

## Consequences

- **Positive**: Operators receive immediate Discord DM visibility for all first-party warnings/errors and third-party errors without touching plugin business code or breaking ADR 0007's `mcp-server` boundary.
- **Negative / Trade-offs**: Failures inside `Decoupled MCP Server` do not trigger DM alerts directly; noisy first-party warnings still produce periodic cooldown-summarized DMs, relying on bounded caches and per-level rate caps to protect Discord DM rate limits.
