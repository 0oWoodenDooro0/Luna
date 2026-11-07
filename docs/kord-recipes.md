# Kord Recipes & API Patterns for Luna

This document provides quick reference recipes and patterns for working with Kord in Luna, avoiding runtime trial-and-error or decompilation.

## Version & Source
- **Version**: `0.19.0-SNAPSHOT` (declared in `gradle/libs.versions.toml`)
- **Repository**: `https://snapshots.kord.dev` (`dev.kord:kord-core`)
- **Note on 0.18.1 vs 0.19.0-SNAPSHOT**: In August 2026, Discord made `channel.application_id` nullable in gateway dispatches. Kord PR #1072 (`9e7dd18`) resolved this by changing `applicationId` to `OptionalSnowflake?`. Stable `0.18.1` (March 2026) still expects non-nullable `OptionalSnowflake`, crashing with `JsonDecodingException: Failed to parse type 'ULong' for input 'null'` on gateway `GUILD_CREATE`. Always retain `0.19.0-SNAPSHOT` until `0.19.0` stable is released.

---

## 1. Slash Command Registration & Subcommands

In Luna, root commands and subcommand groups are registered with Kord's `ChatInputCommandCreateBuilder`:

```kotlin
kord.createGlobalChatInputCommand("mcp", "Luna MCP 多租戶伺服器管理指令") {
    group("client", "MCP 客戶端金鑰管理") {
        subCommand("create", "建立專屬的 MCP Client ID 與金鑰")
        subCommand("show", "查看您擁有的 MCP Client ID 與連線教學")
        subCommand("reset-secret", "重新生成並更換 Client Secret")
        subCommand("revoke", "廢止 Client ID 並清除所有綁定的 Webhook")
    }
    group("webhook", "MCP Webhook 頻道綁定管理") {
        subCommand("add", "綁定 Discord Webhook 至您的 MCP Client") {
            string("tag", "目標頻道識別標籤 (必填，例如 ops)") { required = true }
            string("url", "外部 Webhook 完整網址 (選填，留空則由 Luna 自動於當前頻道建立)") { required = false }
        }
        subCommand("list", "列出已綁定的所有 Webhook 頻道")
        subCommand("remove", "移除指定標籤的 Webhook 綁定") {
            string("tag", "欲移除的標籤 (必填)") { required = true }
        }
    }
}
```

---

## 2. Webhook Operations via Kord REST API

### Automatic Webhook Creation in Channel
```kotlin
val webhook = kord.rest.webhook.createChannelWebhook(
    channelId = channel.id,
    name = "Luna MCP (${tag})",
    reason = "Auto-provisioned via /mcp webhook add by ${user.id}"
)
val webhookUrl = "https://discord.com/api/webhooks/${webhook.id}/${webhook.token}"
```

### Webhook Deletion
```kotlin
// From URL: https://discord.com/api/webhooks/<id>/<token>
kord.rest.webhook.deleteWebhookWithToken(
    webhookId = Snowflake(idString),
    token = tokenString,
    reason = "Deleted via /mcp webhook remove"
)
```

---

## 3. Channel Permission Verification

Checking if a user has `Permission.ManageWebhooks`:

```kotlin
val member = event.interaction.user.asMemberOrNull(guildId)
val permissions = member?.getPermissions()
val canManageWebhooks = permissions?.contains(Permission.ManageWebhooks) == true ||
    permissions?.contains(Permission.Administrator) == true
```

---

## 4. Ephemeral Responses

All secrets and management feedback should be sent ephemerally:

```kotlin
val response = event.interaction.deferEphemeralResponse()
response.respond {
    content = "✅ 操作成功！"
}
```

---

## 5. Testing Kord Slash Commands & Handlers

Do not connect live Discord gateways in tests. Use mock interactions or test repositories and security generators directly:
- `McpSecurity`: Pure cryptographic and hashing functions.
- `McpWebhookRepository` / `McpClientRepository`: In-memory SQLite or H2 tests.
- Verify REST payload serialization without calling Discord endpoints.
