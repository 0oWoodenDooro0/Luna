# Coding Standards

Judgement calls for Luna services and libraries. Mechanical checks (singleton cache bounds, JVM-safe test names, 600-line test file cap, `*ServiceTest.kt` `assertEquals` ban, `.env.example` parity, plugin isolation) are enforced deterministically by `CodingStandardsTest` and linters—do not duplicate them here.

## 1. Daemon State & Transient Failure Eviction

Luna is a long-running Discord bot daemon; non-singleton event/deduplication stores must still bound memory (LRU capacity or TTL) rather than growing unboundedly.

- **Evict transient failures**: Transient errors from external APIs (HTTP 429 rate limits, network timeouts, 5xx server errors) must not be cached as terminal failure states (`FAILED`). Remove transient errors from the cache immediately so subsequent user actions or retries can succeed. Terminal states (`DONE`) should remain cached to prevent redundant API invocations.

## 2. Multi-Project Library Boundaries

Luna is a multi-project Gradle monorepo (`:libs:socialpeek`, `:libs:curtly`, and `:root` bot application).

- **Explicit capability segregation**: When extending a shared library to handle broader input types (such as adding generic web article extraction to `:libs:socialpeek`), do not silently alter existing platform discovery contracts. Keep specialized capability checks (e.g., `SocialPeek.canResolveSocial(url)`) distinct from general fallback checks (`SocialPeek.canResolve(url)`) to avoid unintended scope creep in consuming bot listeners.
- **Fail-open library callers**: Application services calling library extractors or external APIs should isolate individual failure domains (e.g., wrap in `runCatching` with specific logging) rather than terminating event pipelines.

## 3. Discord User Context Preservation

- **Preserve user commentary**: When processing messages that combine external URLs with user comments or annotations, do not discard user commentary in favor of extracted third-party link text. Combine user annotations into the processing pipeline context.
- **Bot boundary disambiguation**: When backtracking or slicing chat history, distinguish between the bot's own workflow markers (`isBotSummaryMessage` checking for `TL;DR:` or previous summary markers) and third-party bots. Third-party bot messages must be filtered from dialogue input rather than terminating history backtracking.
- **Media attachment context**: Messages with blank text must be preserved in conversation context if they carry `attachments`, ensuring multimodal references remain available in the dialogue context.
- **Discord message limits**: All outgoing Discord messages and reply builders must account for Discord's 2,000-character payload limit.
  - **Conversational bot replies**: Direct channel replies and interactive command feedback must truncate gracefully with trailing ellipsis (`...`) before invocation.
  - **Webhook broadcast & ingestion pipelines**: Automated broadcast, streaming, or LLM-generated document delivery (such as `DiscordWebhookBroadcaster`) must not truncate payload content; instead, sequentially chunk messages across paragraph (`\n\n`) and newline (`\n`) boundaries (`DiscordMessageChunker`) to preserve complete content.

## 4. Agent Worktree & Pipeline Seam Isolation

- **Decouple parallel vertical slices**: When decomposing pipeline features into parallel agent worktrees (or subagents), vertical slices must not contend on the same monolithic service file. Isolate distinct pipeline stages into independent collaborator components (e.g., `DialogueNoiseFilter`, `ConversationSliceCollector`, `ContextUrlResolver`) or establish explicit interface seams in trunk tickets before scheduling parallel branches.
- **Collaborator seam extraction**: When extending service pipelines with specialized entity parsing, attachment extraction, media validation, or byte-budget filtering, extract them into dedicated collaborator components with clean companion constants rather than inlining extraction heuristics into service coordinators.
- **Retire obsolete seams & keep test stubs out of `src/main`**: When deepening a module and extracting collaborator components, delete obsolete pass-through wrapper methods (Middle Man), vestigial compatibility parameters (`Any? = null`), and duplicate fallback branches on the coordinator, migrating tests directly to the deep module's public interface. Place test-only seam stubs/fixtures in `src/test`, never in production `src/main` seam files.

## 5. Test Boundaries & Observability

- **Negative boundary test coverage**: Features enforcing data budgets, payload caps (e.g. 10MB limits), format whitelists (e.g. MIME restrictions), or fallback evictions must include explicit negative/rejection test cases (e.g. discarding oversized media, rejecting non-whitelisted formats with spoofed extensions) rather than only testing the happy path.
- **Prompt and pipeline assertion decoupling**: High-level service integration tests must assert coordination, input propagation, and boundary limits (via pattern matching or containment) rather than verbatim prompt templates; test exact prompt wording and markdown headers in isolation within dedicated prompt builder unit tests (e.g., `GeminiPromptBuilderTest`).
- **Modular test suites by concern**: Split new test concerns into focused files (following `GeminiSummarizerTest` / `GeminiSummarizerLookbackTest`) rather than appending to oversized suites.
- **Observable fallback degraded states**: Fail-open fallback routines (such as degrading rich Data API video resolution to basic oEmbed, or defaulting URL resolution to channel chat history) must emit descriptive warning or debug logs with the underlying cause, preventing silent failure black holes during operations.

