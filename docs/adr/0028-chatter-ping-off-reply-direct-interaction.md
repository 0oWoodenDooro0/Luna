# Chatter Ping-Off Replies to Luna as Direct Interactions

Status: accepted

## Context

`ChatterNoiseFilter.isDirectBotMention` (`ChatterNoiseFilter.kt:334-344`) currently recognizes direct interactions only via `message.mentionedUserIds` or raw `<@selfId>` / `<@!selfId>` tokens in `message.content`. On Discord, users routinely toggle **@OFF (ping disabled)** when replying to a bot's message. Because Discord omits the bot from `mentionedUserIds` when ping is disabled, Luna treats direct replies to herself as ambient chatter—subjecting them to channel cooldowns and `Participation Decision` filtering—even though `DeferredCatchUpService.isReplyToLuna` (`DeferredCatchUpService.kt:393-400`) already treats replies to Luna as high-priority catch-up interactions.

> _Extends [ADR 0015](0015-chatter-humanization-debounce-and-silent-reactions.md) (`Burst Debounce Window` / `Asymmetric Reply Threading`) and [ADR 0025](0025-chatter-user-affinity-and-selective-response.md) (`Selective Mention Disengagement`), and updates the `AI Trace` schema from [ADR 0011](0011-ai-trace-jsonl-logging.md)._

## Decision

1. **Unified `Direct Interaction` Classification (Mention + Ping-Off Reply)**:
   - Treat an incoming Discord `MessageType.Reply` whose referenced message is authored by Luna (`selfId`) as a `Direct Interaction` alongside explicit `@Luna` mentions, even when `@OFF` omits Luna from `mentionedUserIds`.
   - **Precedence**: If a message explicitly mentions Luna (including ping-on replies where `mentionedUserIds` contains `selfId`), classify its trigger as `MENTION`; classify it as a ping-off direct reply only when no explicit mention is present.
   - **Kord Resolution & Safety**: Require `message.type == MessageType.Reply` (preventing Discord forwarded messages from matching). Check `message.referencedMessage` first; if `null` and `message.messageReference` points to the same channel, attempt a bounded suspend fallback via `message.channel.getMessageOrNull(refId)`. Any lookup failure, missing/deleted target, or cross-channel reference fails safe to ambient (`false`).
   - Share the reply-to-Luna check with `DeferredCatchUpService` so live chatter and `Deferred Catch-Up` stay consistent.

2. **Exclude `Summary Pipeline` Outputs from Ping-Off Escalation**:
   - If the referenced Luna message is identified as a bot summary (`DiscordDialogueContextEngine.isBotSummaryMessage` / `SummaryMessageRegistry`), do **not** escalate a ping-off reply to a `Direct Interaction`.
   - Such replies fall back to ambient `Participation Decision` so community members reacting to a summary among themselves do not force a Luna response, while genuine follow-up questions can still be picked up organically. Explicit `@Luna` (ping-on) replies to a summary remain `Direct Interaction` (`MENTION`).

3. **No Age Cutoff on Replied-To Luna Messages**:
   - Do not impose a staleness/recency cap on the referenced Luna message. Users explicitly replying to an older Luna message are addressing her directly, `Reply Reference Chaining` already resolves out-of-window targets for context, and `DeferredCatchUpService.isReplyToLuna` likewise imposes no age cutoff.

4. **Shared Direct-Interaction Downstream Behavior & `Asymmetric Reply Threading`**:
   - Once classified as a `Direct Interaction`, a ping-off reply follows the exact same downstream path as an explicit `@Luna` mention: bypasses ambient cooldown and `Participation Decision`, routes `COOL`/`DISLIKED` users through `Selective Mention Disengagement` (ADR-0025), uses mention debounce and burst isolation (`BoundedChannelBurstCoordinator`), enables `Tiered Video Latency Policy` agentic video for >5m videos, retains `EMPTY_OR_EMOJI_ONLY` context eligibility (`ChatterNoiseFilter.isContextMessage`), and updates `Conversation Momentum` (ADR-0027) and `Deferred Catch-Up`.
   - Retain `pingRepliedUser = true` when `Asymmetric Reply Threading` attaches a reply reference (`ChatterService.kt:534`), matching `GLOSSARY.md`: users disable ping on bots because bots do not receive notifications, not because they want Luna's threaded reply to go unnoticed in a busy channel.

5. **`AI Trace` Observability (`AiTraceTrigger.DIRECT_REPLY`)**:
   - Add `DIRECT_REPLY` to `AiTraceTrigger` (`AiTraceModels.kt:19-23`) so top-level JSONL traces cleanly distinguish `MENTION` vs. `DIRECT_REPLY` vs. `AMBIENT` in `jq`/`rg` queries, while keeping `AiTraceDiscordContext.isDirectMention = true` for both direct triggers to preserve single-boolean direct-interaction filtering. Update `docs/architecture/ai-tracing.md` accordingly.

## Consequences

- **Positive**: Natural Discord ping-off replies to Luna receive reliable, immediate responses without forcing users to leave pings on; summary-message commentary avoids unwanted bot hijacking; `AI Trace` cleanly separates mention vs. ping-off reply volume without breaking boolean `discord.isDirectMention` filters.
- **Negative / Trade-offs**: Cache-miss `MessageType.Reply` messages incur at most one bounded Discord REST lookup (`getMessageOrNull`) to inspect the referenced author; existing `AI Trace` queries that filter strictly on `.trigger == "MENTION"` will need to include `"DIRECT_REPLY"` when aggregating all direct interactions (or filter on `.discord.isDirectMention == true`).
