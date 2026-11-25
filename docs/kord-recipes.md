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

### Event Listener Testing (`MutableSharedFlow`, `runTest`)
```kotlin
// 1. Wait for all kord.on<T> listeners to subscribe before emitting test events
val eventsFlow = MutableSharedFlow<Event>(extraBufferCapacity = 16)
while (eventsFlow.subscriptionCount.value < expectedCount) {
    withContext(Dispatchers.Default) { delay(10L) }
}
eventsFlow.emit(testEvent)

// 2. Use real-time delay when polling wall-clock deadlines or awaiting Dispatchers.Default work
//    (runTest virtual delay advances virtual time without yielding to real background threads)
withContext(Dispatchers.Default) { delay(10L) }
```

---

## 6. Messages, Replies, Reactions, Stickers & Guild Emojis

> Context7 (`/kordlib/kord`) only indexes basic wiki/README snippets and omits Kord `0.19.0-SNAPSHOT` entity/behavior signatures. Use the verified patterns below before inspecting Kord JARs.

### Reply, Allowed Mentions & Intervening Messages (`Message`, `MessageChannelBehavior`)
```kotlin
import dev.kord.common.entity.AllowedMentionType
import dev.kord.core.behavior.reply
import dev.kord.core.behavior.channel.createMessage
import dev.kord.rest.builder.message.allowedMentions
import kotlinx.coroutines.flow.toList

// Plain channel message vs explicit Discord reply reference (restrict allowedMentions to prevent @everyone/@here/role pings)
channel.createMessage {
    this.content = text
    allowedMentions {
        add(AllowedMentionType.UserMentions)
    }
}
triggerMessage.reply {
    this.messageReference = triggerMessage.id
    this.content = text
    allowedMentions {
        add(AllowedMentionType.UserMentions)
        repliedUser = true
    }
}

// Fetch messages posted after a trigger message
val after = message.channel.getMessagesAfter(message.id, limit = 20).toList()

// Resolve a MessageChannel by Snowflake ID (no getChannelOfOrNull / getChannelOrNull in Kord 0.19.0-SNAPSHOT)
val channel = runCatching { kord.getChannelOf<MessageChannel>(channelId) }.getOrNull()
// Channel name extraction: prefer GuildChannel.name, fall back to channel.data.name.value
```

### Cache-Only vs REST Entity Lookups (`EntitySupplyStrategy`)
```kotlin
import dev.kord.core.supplier.EntitySupplyStrategy

// Default lookups (message.getGuildOrNull(), kord.getUser(id), kord.getChannel(id)) use cacheWithRestFallback
// and WILL trigger a REST call when the entity is not cached.
// Pass EntitySupplyStrategy.cache explicitly whenever REST fallback must be disabled or budgeted:
val cachedUser = kord.getUser(userId, strategy = EntitySupplyStrategy.cache)
val cachedChannel = kord.getChannel(channelId, strategy = EntitySupplyStrategy.cache)
```

### Reactions (`ReactionEmoji`, `Message.addReaction`)
```kotlin
import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.ReactionEmoji

// Unicode emoji vs guild custom emoji (id is Snowflake, isAnimated is Boolean)
val unicodeEmoji = ReactionEmoji.Unicode("👍")
val customEmoji = ReactionEmoji.Custom(id = Snowflake("123456"), name = "blobcat", isAnimated = false)
message.addReaction(unicodeEmoji)
```

### Guild Custom Emojis (`Message.getGuildOrNull`, `EmojisUpdateEvent`)
```kotlin
val guild = message.getGuildOrNull() // null in DMs or unavailable guilds; falls back to REST if uncached (use EntitySupplyStrategy.cache to avoid REST)
val availableEmojis = guild?.emojis?.toList().orEmpty()
    .filter { it.isAvailable && !it.name.isNullOrBlank() }
// Canonical Discord tag: if (emoji.isAnimated) "<a:${emoji.name}:${emoji.id.value}>" else "<:${emoji.name}:${emoji.id.value}>"

// Cache invalidation on guild emoji updates: EmojisUpdateEvent(event.guildId, event.emojis: Set<GuildEmoji>)
// GuildEmoji fields: id (Snowflake), name (String?), isAnimated (Boolean), isAvailable (Boolean)
kord.on<EmojisUpdateEvent> { cache.invalidate(guildId) } // event.emojis holds updated GuildEmoji set
```

### Application Emojis (`Kord.emojis`)
```kotlin
import dev.kord.core.entity.ApplicationEmoji
import kotlinx.coroutines.flow.toList

// Fetches bot-owned Discord Application Emojis via REST; ApplicationEmoji implements CustomEmoji
// Fields: id (Snowflake), name (String?), isAnimated (Boolean), isAvailable (Boolean)
val appEmojis: List<ApplicationEmoji> = kord.emojis.toList()
    .filter { it.isAvailable && !it.name.isNullOrBlank() }
// No Discord gateway event exists for Application Emoji updates — cache with a TTL instead of event invalidation.
```

### Stickers & Embeds (`Message.stickers`, `MessageStickerType`, `Embed`)
```kotlin
import dev.kord.common.entity.MessageStickerType

// StickerItem fields: id (Snowflake), name (String), formatType (MessageStickerType)
// MessageStickerType cases: PNG, APNG, GIF (CDN: "https://cdn.discordapp.com/stickers/${sticker.id.value}.png|.gif"),
//   LOTTIE (vector JSON, degrade to "[貼圖: ${sticker.name}]"), is MessageStickerType.Unknown
message.stickers.forEach { sticker -> /* check sticker.formatType */ }

// Embed fields (probe defensively with runCatching on partial/malformed embeds):
// embed.url, embed.title, embed.provider?.name, embed.provider?.url,
// embed.image?.url, embed.image?.proxyUrl, embed.thumbnail?.url, embed.thumbnail?.proxyUrl, embed.video?.url
```

---

## 7. Gateway Lifecycle & Pre-`ReadyEvent` Guard

In `LunaHost.start()` (`Main.kt`), `pluginLoader.initializePlugins(context)` runs **before** `kordClient.login { ... }`. Any plugin or background loop started during `init()` that calls Gateway-backed APIs (such as `kord.editPresence { ... }`) before the Gateway WebSocket connects will throw `UninitializedPropertyAccessException: lateinit property socket has not been initialized`.

Guard Gateway calls with a `ReadyEvent` gate (see `KordSchedulePresencePort` and `OwnerErrorNotifier`):
```kotlin
private val gatewayReady = AtomicBoolean(false)
private val pendingState = AtomicReference<State?>(null)

fun registerReadyListener(scope: CoroutineScope): Job? =
    runCatching {
        kord.on<ReadyEvent>(scope = scope) { onGatewayReady() }
    }.getOrNull()

suspend fun onGatewayReady() {
    gatewayReady.set(true)
    val latest = pendingState.get() ?: return
    syncToGateway(latest) // also re-syncs after Gateway reconnects
}

suspend fun update(state: State) {
    pendingState.set(state)
    if (!gatewayReady.get()) return // defer until ReadyEvent
    syncToGateway(state)
}
```
Cancel the `ReadyEvent` listener `Job` in `shutdown()`.


