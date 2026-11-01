# Webhook Replacement Recognition in Summary Pipeline

## Context
When SocialPreview replaces a social media link with a clean URL and rich embed using a Discord webhook masquerading as the user, the original message is deleted. Because Discord webhook messages carry `webhookId != null` and `author.isBot == true`, DialogueNoiseFilter previously discarded all webhook messages as noise. Consequently, adding a Summary Reaction to a Webhook Replacement produced no response, and conversation slice backtracking excluded these messages.

## Decision
1. **Selective Webhook Permeability**:
   - `DialogueNoiseFilter` and `SummaryService` permit Luna's own Webhook Replacement messages while continuing to exclude third-party webhooks (e.g. CI/CD or notification bots).
   - A webhook is identified as a Luna Webhook Replacement by matching its webhook ID against Luna's managed webhook cache or verifying that its creator is Luna (`creatorId == kord.selfId` or name `"Luna Preview"`).
2. **In-Memory Webhook Cache**:
   - Webhook identity checks use an in-memory cache to prevent redundant Discord REST API lookups and avoid rate limit exhaustion during conversation slice backtracking.
3. **Dialogue Context and Attribution**:
   - Webhook Replacement messages qualify both as direct targets for Summary Reactions and as valid conversational turns in Dual-Boundary Backtracking.
   - Dialogue prompts preserve the masqueraded author name (`message.author?.username`) to ensure correct participant viewpoint attribution in LLM summaries.
