# Coding Standards

Judgement calls for Luna services and libraries. Enforced by reviewer agents; mechanical checks belong in automated linters and CI.

## 1. Daemon State & Cache Bounds

Luna is a long-running Discord bot daemon. Memory leaks and unbounded collections degrade stability over time.

- **Bounded in-memory caches**: Every state store or deduplication cache keyed by message IDs, user IDs, or transient events must enforce an upper bound or eviction policy (e.g., `BoundedStatusCache`, LRU LinkedHashMap with capacity limit, or TTL expiration). Never use unbounded `ConcurrentHashMap` or bare `MutableMap` collections for event state.
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

## 5. Kotlin Test Naming & Observability

- **JVM-safe test identifiers**: While Kotlin allows spaces in backticked test method names, class bytecode strictly forbids JVM-illegal characters: `<`, `>`, `:`, `"`, `/`, `\`, `|`, `?`, `*`. Never use mathematical comparison symbols (e.g., `<= 300` or `> 300`) in test function names; use natural descriptive phrases instead (e.g., `when duration is at most 300 seconds`).
- **Negative boundary test coverage**: Features enforcing data budgets, payload caps (e.g. 10MB limits), format whitelists (e.g. MIME restrictions), or fallback evictions must include explicit negative/rejection test cases (e.g. discarding oversized media, rejecting non-whitelisted formats with spoofed extensions) rather than only testing the happy path.
- **Prompt and pipeline assertion decoupling**: Integration tests (such as `SummaryServiceTest`) must assert service-level coordination, input propagation, and boundary limits rather than asserting exhaustive verbatim substrings of prompt templates or coordinated dialogue slices (`input.content`). Avoid brittle `assertEquals` on formatted message contents in high-level service tests; use pattern matching or substring containment (`assertTrue(content.contains(...))`) so prompt and formatting iterations do not cause cascading test fragility across the codebase. Exact prompt wording, markdown headers, and structural instructions must be tested in isolation within dedicated prompt builder unit tests (e.g., `GeminiPromptBuilderTest`).
- **Observable fallback degraded states**: Fail-open fallback routines (such as degrading rich Data API video resolution to basic oEmbed, or defaulting URL resolution to channel chat history) must emit descriptive warning or debug logs with the underlying cause, preventing silent failure black holes during operations.

