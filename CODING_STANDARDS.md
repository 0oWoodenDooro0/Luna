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
- **Discord message limits**: All outgoing Discord messages and reply builders must account for Discord's 2,000-character payload limit, truncating gracefully with trailing ellipsis (`...`) before invocation.

## 4. Agent Worktree & Pipeline Seam Isolation

- **Decouple parallel vertical slices**: When decomposing pipeline features into parallel agent worktrees (or subagents), vertical slices must not contend on the same monolithic service file. Isolate distinct pipeline stages into independent collaborator components (e.g., `DialogueNoiseFilter`, `ContextUrlResolver`) or establish explicit interface seams in trunk tickets before scheduling parallel branches.

