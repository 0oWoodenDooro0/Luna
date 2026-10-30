# SocialPeek

<p align="center">
  <b>純 Kotlin (JVM) 社群平台貼文解析與多媒體萃取函式庫</b>
</p>

---

## 🌟 特色與設計原則

1. **零外部機器人框架依賴**：完全不依賴任何 Discord / Telegram / Kord / JDA 框架，純粹專注於多平台資料解析。
2. **極致簡潔的呼叫介面 (Deep Module)**：只需 `SocialPeek.peek(url)` 即可自動匹配平台並提取結構化資料。
3. **網址追蹤參數淨化與正規化 (Sanitizer)**：自動去除 `utm_*`、`fbclid`、`igsh`、`si`、`spm_id_from`、`mibextid` 等行銷追蹤參數，並正規化網址路徑與網域。
4. **策略模式 (Strategy Pattern)**：各平台各自實作 `PlatformResolver`，易擴展、高維護性。
5. **協程友善 (Kotlin Coroutines)**：原生 `suspend fun` 支援與非阻塞 I/O。
6. **最新依賴與技術棧**：Kotlin 2.x、Ktor 3.5.x、kotlinx.coroutines 1.10.x、kotlinx.serialization、Jsoup 1.21.x。
7. **JitPack 開箱即用**：內建 `maven-publish` 配置。

---

## 📦 安裝方式 (JitPack)

在專案的 `settings.gradle.kts` 或根目錄 `build.gradle.kts` 中添加 JitPack 倉庫：

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
```

在模組的 `build.gradle.kts` 中加入依賴：

```kotlin
dependencies {
    implementation("com.github.0oWoodenDooro0:SocialPeek:v0.5.0")
}
```

---

## 🚀 快速上手

### 1. 簡易解析 (開箱即用)

```kotlin
import dev.socialpeek.SocialPeek

suspend fun main() {
    // 解析 Facebook 貼文 / 影片 / Reel / 社團
    val fbPost = SocialPeek.peek("https://www.facebook.com/zuck/posts/pfbid02517vL7rZ9kFq71k51aP6k4R7Y6oBw8m?mibextid=ZbWKwL")
    println(fbPost.content)
    println(fbPost.author.displayName)
    println(fbPost.cleanUrl) // 乾淨無追蹤參數的網址

    // 解析 X (Twitter) 貼文
    val tweet = SocialPeek.peek("https://x.com/jack/status/20?s=20")
    println(tweet.content)
    println(tweet.author.displayName)
    println(tweet.cleanUrl)

    // 解析 Bilibili 影片/動態
    val bili = SocialPeek.peek("https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.788")
    println(bili.title)
    println(bili.media)

    // 安全解析 (失敗時回傳 null)
    val post = SocialPeek.peekOrNull("https://www.instagram.com/p/invalid-link/")
    if (post == null) {
        println("無法解析此連結")
    }

    // 單純網址追蹤參數淨化 (無須網路請求)
    val clean = SocialPeek.cleanUrl("https://youtu.be/dQw4w9WgXcQ?si=abcdef&t=30")
    println(clean) // https://youtu.be/dQw4w9WgXcQ?t=30
    println(SocialPeek.hasTrackingParams("https://x.com/user/status/123?s=20")) // true
}
```

### 2. 進階自訂與擴展 (Builder 模式)

您可以透過 `SocialPeek.builder()` 抽換底層 HTTP 客戶端、新增自訂的社群平台 Resolver，或是選擇性停用預設的 Resolver：

```kotlin
import dev.socialpeek.SocialPeek
import dev.socialpeek.network.KtorSocialPeekHttpClient
import io.ktor.client.*
import io.ktor.client.engine.cio.*

val customKtorClient = HttpClient(CIO) {
    // 自訂超時、代理伺服器 (Proxy) 或其他外掛
}

val socialPeek = SocialPeek.builder()
    .httpClient(KtorSocialPeekHttpClient(customKtorClient))
    // .addResolver(MyCustomPlatformResolver())
    .build()

suspend fun parse() {
    val post = socialPeek.peek("https://threads.net/@user/post/...")
}
```

---

## 🌐 支援平台

| 平台 | 支援的 URL 形式範例 | 提取內容 |
| :--- | :--- | :--- |
| **Facebook** | `facebook.com/.../posts/...`, `.../reel/...`, `.../videos/...`, `.../watch/?v=...`, `fb.watch/...`, `photo.php?fbid=...`, `permalink.php?story_fbid=...`, `groups/.../posts/...`, `share/p/...` | 作者、貼文內文/標題、圖片、影片、互動數據 (觀看/心情/留言/分享)、社團 (Community)、去追蹤 cleanUrl |
| **X (Twitter)** | `x.com/.../status/...`, `twitter.com/...` | 作者、推文文字、圖片/影片預覽、統計數據 (轉推/點讚)、去追蹤 cleanUrl |
| **Instagram** | `instagram.com/p/...`, `.../reel/...`, `.../share/p/...` | 圖片 (多圖輪播)、Reel 封面與作者資訊、去追蹤 cleanUrl |
| **Threads** | `threads.net/@user/post/...`, `threads.com/share/...` | 作者、貼文內容、輪播圖片 (多張)、影片預覽、去追蹤 cleanUrl |
| **Bilibili** | `bilibili.com/video/BV...`, `.../opus/...`, `b23.tv/...` | 標題、簡介、封面圖、作者名稱、播放/硬幣/彈幕等數據、去追蹤 cleanUrl |
| **YouTube** | `youtube.com/watch?v=...`, `youtu.be/...`, `.../shorts/...`, `.../live/...` | 標題、作者/頻道名、縮圖、oEmbed 詮釋資料、去追蹤 cleanUrl |
| **Reddit** | `reddit.com/r/.../comments/...`, `redd.it/...`, `reddit.com/r/.../s/...` (短網址分享) | 標題、內文 (Selftext)、多圖畫廊 (Gallery)、子版 (Subreddit)、看板圖示 (communityIcon)、點讚/留言數、去追蹤 cleanUrl |

---

## 🧪 執行測試

本專案附帶完整的單元測試與 Mock 網路測試，可直接執行：

```bash
./gradlew test
```

---

## 📄 授權協議

本專案採用 [Apache License 2.0](LICENSE) 授權。
