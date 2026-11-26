# Discord Mention Resolution and Unified Display Identity

Status: accepted

## Context

`Dialogue Context Engine` (`DiscordDialogueContextEngine`) currently passes raw Discord `message.content` verbatim into formatted transcripts for both `Chatter Pipeline` (reply generation, `Participation Decision`, `Memory Distillation`, `Deferred Catch-Up`) and `Summary Pipeline`. Consequently, raw Discord mention tokens (`<@id>`, `<@!id>`, `<@&roleId>`, `<#channelId>`) remain unresolved snowflake IDs. In addition, `resolveSpeaker` only extracts Discord account `username` rather than server nicknames or global display names, `ChatterPromptBuilder` does not explain how to interpret or emit mentions (nor Luna's own Discord UID in the reply prompt), and `ChatterChannelPort` sends Discord messages without an explicit `allowedMentions` policy.

As a result, Luna cannot reliably tell who a user is mentioning—especially when the mentioned member has not spoken in the current `Working Memory Window` or goes by a server nickname—and `Participation Decision` cannot distinguish directed mentions of other users from bot or role mentions.

## Decision

1. **Policy-Driven `Mention Resolution` in `Dialogue Context Engine`**:
   - Resolve Discord user mentions (`<@id>` and `<@!id>`, including Luna's own `kordSelfId`), role mentions (`<@&roleId>`), and channel mentions (`<#channelId>`) inline during transcript formatting—including inside `Reply Reference Chaining` excerpts—without mutating raw `Message.content` or altering `ChatterNoiseFilter.isDirectBotMention` trigger behavior (preserving ADR-0010).
   - Gate output formatting via `DialogueContextPolicy`:
     - **`Chatter Pipeline`** (reply generation, `Participation Decision`, `Memory Distillation`, `Deferred Catch-Up`): expand inline while preserving stable user IDs so the model can correlate speakers, invoke `Agentic Memory Retrieval` (`target_user_id`), and tag users when warranted:
       - Human/other bot user: `@<displayName> (uid:<id>)`
       - Luna self (`kordSelfId`): `@Luna [YOU] (uid:<selfId>)`
       - Role: `@<roleName> (role)`
       - Channel: `#<channelName>`
     - **`Summary Pipeline`**: expand inline to clean human-readable forms (`@<displayName>`, `@<roleName>`, `#<channelName>`) without `(uid:<id>)` annotations.
   - Resolve IDs using message-embedded `mentionedUsers` and Kord's local cache first, with per-slice ID deduplication and a bounded REST fallback cap per slice; degrade gracefully to the original raw token (e.g. `<@123456>`) on lookup failure, missing permissions, or departed members.

2. **Unified Guild Display Identity Priority**:
   - Standardize display-name resolution across both `Mention Resolution` and `Dialogue Context Engine` speaker headers (`resolveSpeaker`) to `member.effectiveName ?: user.globalName ?: user.username` (server nickname → global display name → username).
   - Because `ChatterPromptBuilder.formatSpeakerHeader` retains `<displayName> (uid:<id>)`, this stays compatible with `UnderstandingPreInjector`'s `SPEAKER_HEADER_REGEX` (`^(.+?)\s+\(uid:(\d+)\)(?::|\s|$)`) while ensuring speaker names match mentioned names.

3. **Expand `Understanding Pre-Injection` to Mentioned Human Users**:
   - Extend `UnderstandingPreInjector` to extract human users mentioned in the latest message as `Social Understanding` pre-injection candidates—prioritized ahead of older bystander speakers in the `Working Memory Window`—while remaining bounded by the existing `maxChars` budget and per-category entry limits.

4. **Prompt Guidance and Outgoing `allowedMentions` Guardrail**:
   - Update `ChatterPromptBuilder` `SYSTEM_INSTRUCTION` to supply Luna's own Discord UID, explain the inline mention format, and instruct restrained ping etiquette (refer to people by plain name in normal conversation; emit `<@uid>` only when intentionally notifying a specific person).
   - Enforce Kord `allowedMentions` on `ChatterChannelPort.sendMessage` and `ChatterChannelPort.sendReply` to allow `Users` only and unconditionally block `@everyone`, `@here`, and `Roles` pings. On `sendReply`, control `repliedUser` via an explicit `pingRepliedUser: Boolean = false` flag—enabling `repliedUser = true` only when replying to a direct `@Luna` mention or direct reply to Luna (in both live `Asymmetric Reply Threading` and `Deferred Catch-Up`), and keeping `repliedUser = false` for ambient replies.

## Consequences

- **Positive**: Luna accurately understands who is being mentioned across replies, `Participation Decision`, `Memory Distillation`, and `Deferred Catch-Up`; `Summary Pipeline` also eliminates raw snowflake tokens without exposing internal UIDs; pre-injecting `Social Understanding` for mentioned users gives immediate relational familiarity without an extra tool round-trip; `allowedMentions` eliminates accidental `@everyone` or role pings.
- **Negative / Trade-offs**: Inline `@Name (uid:<id>)` expansion slightly increases prompt token count on mention-heavy messages; guild nickname lookups add a small bounded cache/REST step during context assembly when members are not already cached.
