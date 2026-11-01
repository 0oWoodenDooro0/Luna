# 🌔 Luna - 現代化 Discord 機器人與模組化外掛服務平台

Luna 是一款基於 Kotlin、Kord 與 Ktor 開發的現代化 Discord 機器人與多功能服務平台。專案採用 **模組化外掛架構 (Modular Plugin Architecture)** 與 **Gradle Monorepo** 架構，以單一執行個體與統一的 `PluginContext` 邊界提供 AI 影音/對話摘要、多平台社群預覽、短網址與數據分析控制台、以及派對互動遊戲。

---

## ✨ 核心特色 (Features)

### 🧩 模組化外掛架構 (Modular Plugin Architecture)
- **統一生命週期管理**：基於 Java `ServiceLoader` SPI 機制 (`LunaPlugin`)，外掛完全解耦、獨立發現與載入。
- **Plugin Context 隔離邊界**：主程式 (`LunaHost`) 透過 `PluginContext` 提供受管的 Kord、Ktor Routing、SQLite 連線池、協程範疇 (`CoroutineScope`) 與型別安全設定存取。
- **四大內建外掛**：
  1. `curtly`：短網址服務、多維度點擊分析與 Web 控制台。
  2. `social-preview`：社群媒體貼文解析與自動預覽。
  3. `summarizer`：對話回溯與 YouTube 多模態 AI 摘要。
  4. `undercover`：誰是臥底文字派對遊戲。

### 🤖 AI 對話與影音多模態摘要 (Summarizer Plugin)
- **表情符號反應觸發**：在任意訊息加入指定 Emoji（預設 📝）即可觸發 AI 摘要工作流。
- **雙邊界回溯演算法 (Dual-Boundary Backtracking)**：
  - 向上回溯收集對話脈絡（上限預設 50 則，字元預算上限 20,000 字）。
  - 自動偵測閒置時間間隔（預設 15 分鐘）與前次摘要記錄，精準切分話題。
- **對話雜訊過濾 (Dialogue Noise Filter)**：過濾斜線指令、第三方機器人噪音、自訂表情符號與系統事件，保留人類對話實質內容。
- **聊天圖片多模態理解 (Chat Image Multimodal Understanding)**：
  - 自動擷取回溯視窗內的 Discord 圖片附件與 CDN 連結（支援最多 3 張、單圖 10MB、嚴格 MIME 驗證）。
  - 保留發言者發圖脈絡，並與 YouTube 影音摘要無縫共存與優雅降級。
- **YouTube 多模態深度摘要**：
  - **自適應影音處理 (Adaptive Video Processing)**：短影片採用靜態處理，長影片自動切換為 Gemini Agentic 模式以節省高達 88% token 消耗。
  - **影片重點提示 (Video Focus Prompting)**：引導模型提取影像與音訊關鍵論點。
  - **創作者描述解析 (Creator Description Ingestion)**：將作者章節時間軸與影片說明作為語意前置資訊。
  - **觀眾反饋注入 (Audience Feedback Injection)**：透過 YouTube Data API 擷取熱門留言與觀眾反應，補充討論面向。

### 🌐 社群貼文自動解析與預覽 (Social Preview Plugin & SocialPeek)
- **多平台即時解析**：監聽 Discord 訊息中的連結，生成格式化 Embed 預覽。支援 **Threads, Bilibili, Reddit, YouTube, Facebook, X (Twitter), Instagram** 與任意通用網頁 (`GenericWebResolver`)。
- **隱私與乾淨網址**：自動過濾 Bilibili 的 `trackid` / `track_id` 等追蹤參數。
- **伺服器偏好設定**：支援 `/preview` 指令開關特定頻道的自動預覽行為。

### 🔗 短網址與分析控制台 (Curtly Plugin)
- **Discord Slash 指令**：
  - `/shorten`：將長網址縮短為短鏈接（支援自訂別名、有效期限、最大點擊限制）。
  - `/myurls`：查詢個人名下建立的所有短網址與即時點擊成效。
- **Discord OAuth2 一鍵登入**：Web 控制台與 Discord 帳號無縫綁定。
- **玻璃擬態 Dashboard**：暗色系極簡 Web 介面，支援即時搜尋、短網址管理、一鍵複製與詳細點擊分析圖表。
- **多維度點擊分析**：追蹤每日點擊趨勢、熱門來源網站 (Top Referrers)、裝置與瀏覽器 (User-Agent)、點擊 IP 與時間戳日誌。

### 🕵️ 誰是臥底文字派對遊戲 (Undercover Plugin)
- **Discord 頻道文字互動遊戲**：支援多人加入、私訊分發平民與臥底詞彙。
- **互動式選單投票**：透過 Discord Select Menu 進行匿名投票表決，自動統計票數與判斷勝負。
- **指令支援**：`/undercover`（發起遊戲）、`/reveal`（揭曉身分）。

---

## 🛠️ 環境變數設定 (Environment Variables)

啟動 Luna 之前，請設定以下環境變數（或於專案根目錄 `.env` / `application.yml` 中配置）：

| 環境變數 | 說明 | 預設值 / 範例 |
| :--- | :--- | :--- |
| `DISCORD_TOKEN` | Discord 機器人 Token (**必填**) | `MTEyM...` |
| `DISCORD_CLIENT_ID` | Discord Application ID (OAuth2 登入用，**必填**) | `123456789012345678` |
| `DISCORD_CLIENT_SECRET` | Discord Client Secret (OAuth2 登入用，**必填**) | `xxxxxx` |
| `DISCORD_REDIRECT_URI` | OAuth2 重導向網址 | `http://localhost:8080/api/auth/discord/callback` |
| `BASE_URL` | 短網址基底網域 | `http://localhost:8080/s/` |
| `PORT` | Web 伺服器通訊埠 | `8080` |
| `GEMINI_API_KEY` | Google Gemini API 金鑰 (用於 AI 摘要) | `AIzaSy...` |
| `GEMINI_MODEL` | Gemini 模型名稱 | `gemini-3.5-flash-lite` |
| `YOUTUBE_API_KEY` | YouTube Data API v3 金鑰 (用於長度、留言與描述) | `AIzaSy...` (若未設定則平滑降級) |
| `SUMMARY_EMOJI` | 觸發 AI 摘要的 Emoji | `📝` |
| `SUMMARY_MAX_HISTORY_MESSAGES` | AI 摘要回溯訊息數上限 (1-100) | `50` |
| `SUMMARY_INACTIVITY_GAP_MINUTES` | AI 摘要歷史回溯閒置中斷時間 (分鐘) | `15` |
| `SUMMARY_MAX_CHARACTER_BUDGET` | AI 摘要歷史訊息累計字元上限 | `20000` |

---

## ⚙️ Discord 開發者後台設定步驟

1. 前往 [Discord Developer Portal](https://discord.com/developers/applications) 並點選您的 Application。
2. **取得 Client ID**：在 `General Information` 頁面複製 `APPLICATION ID`（即 `DISCORD_CLIENT_ID`）。
3. **取得 Client Secret**：前往 `OAuth2` -> `General` 頁面，點擊 `Reset Secret` 取得 `DISCORD_CLIENT_SECRET`。
4. **設定 Redirects**：在 `OAuth2` -> `General` 的 `Redirects` 區塊添加：
   - 本地測試：`http://localhost:8080/api/auth/discord/callback`
   - 正式環境：`https://your-domain.com/api/auth/discord/callback`
   - 點擊右下角 `Save Changes` 儲存變更。
5. **啟用 Privileged Gateway Intents**：在 `Bot` 頁面勾選 `Message Content Intent`，以便機器人讀取文字訊息進行社群預覽與對話摘要。

---

## 🚀 快速開始與專案執行

### 準備工作：建立環境變數檔 `.env`

```bash
# 複製範本檔
cp .env.example .env
```
編輯 `.env` 並填入您的 Discord Token 等設定：
```env
DISCORD_TOKEN=你的_DISCORD_TOKEN
DISCORD_CLIENT_ID=你的_DISCORD_CLIENT_ID
DISCORD_CLIENT_SECRET=你的_DISCORD_CLIENT_SECRET
DISCORD_REDIRECT_URI=http://localhost:8080/api/auth/discord/callback
BASE_URL=http://localhost:8080/s/
PORT=8080
GEMINI_API_KEY=你的_GEMINI_API_KEY
YOUTUBE_API_KEY=你的_YOUTUBE_API_KEY
```

---

### 方法 A：使用 Docker Compose 啟動（推薦）

專案內建 [`docker-compose.yml`](file:///home/user/IdeaProjects/Luna/docker-compose.yml)，能自動讀取 `.env` 並掛載 `./data` 磁區保存 SQLite 資料庫與日誌。

#### 1. 一鍵啟動
```bash
docker compose up -d
```

#### 2. 常用維護指令
- **檢視即時日誌**：
  ```bash
  docker compose logs -f
  ```
- **重新啟動服務**：
  ```bash
  docker compose restart
  ```
- **更新映像檔並重啟**：
  ```bash
  docker compose pull
  docker compose up -d
  ```
- **停止並移除容器**：
  ```bash
  docker compose down
  ```

---

### 方法 B：使用 Docker CLI 啟動

#### 方式 1：直接拉取 GitHub Container Registry (GHCR) 預編譯映像檔
```bash
# 1. 拉取最新映像檔
docker pull ghcr.io/0owoodendooro0/luna:latest

# 2. 啟動容器
docker run -d \
  --name luna-bot \
  --restart unless-stopped \
  -p 8080:8080 \
  --env-file .env \
  -v $(pwd)/data:/app/data \
  ghcr.io/0owoodendooro0/luna:latest
```

#### 方式 2：本地自行構建 Docker 映像檔並啟動
```bash
# 1. 本地多階段構建映像檔
docker build -t luna:latest .

# 2. 啟動容器
docker run -d \
  --name luna-bot \
  --restart unless-stopped \
  -p 8080:8080 \
  --env-file .env \
  -v $(pwd)/data:/app/data \
  luna:latest
```

> [!TIP]
> **資料持久化**：使用 `-v $(pwd)/data:/app/data` 磁區掛載，可確保容器重新啟動或更新版本時，SQLite 資料庫（`data/urls.db`）與結構化日誌（`data/logs/`）完整保留。

---

### 方法 C：本機直接執行（需要 Java JDK 17+）

若不想使用 Docker，也可直接透過 Gradle 執行：

```bash
# 1. 複製專案與建置
git clone https://github.com/0oWoodenDooro0/Luna.git
cd Luna
./gradlew build

# 2. 設定環境變數並啟動
export DISCORD_TOKEN="你的_DISCORD_TOKEN"
export DISCORD_CLIENT_ID="你的_DISCORD_CLIENT_ID"
export DISCORD_CLIENT_SECRET="你的_DISCORD_CLIENT_SECRET"
export BASE_URL="http://localhost:8080/s/"

./gradlew run
```

---

### 🛠️ Monorepo 構建與測試指令 (Building & Testing)

本專案採用 Gradle 多專案 Monorepo 架構，支援跨模組統一構建、驗證與測試：

- **建置全專案**：
  ```bash
  ./gradlew build
  ```
- **執行全專案檢查與測試 (包含所有模組共 203 項測試)**：
  ```bash
  ./gradlew clean check test
  ```
- **獨立測試各子模組**：
  ```bash
  # 測試 SocialPeek 社群解析模組 (75 tests)
  ./gradlew :libs:socialpeek:test

  # 測試 Luna 核心與外掛模組 (128 tests)
  ./gradlew test
  ```
- **代碼格式檢查 (Kotlinter)**：
  ```bash
  ./gradlew formatKotlin
  ./gradlew lintKotlin
  ```
- **本機驗證 Maven 發布 (Maven Local)**：
  ```bash
  ./gradlew publishToMavenLocal
  ```
- **CI Docker 構建同等驗證**：
  ```bash
  docker build -t luna:ci-test .
  ```

---

## 📖 使用方法 (Usage)

### 1. 🤖 在 Discord 使用

- **短網址指令**：
  - `/shorten url:https://example.com/very-long-url custom_key:my-link expires_in:86400 max_clicks:100`：縮短網址。
  - `/myurls`：查看自己建立的所有短網址與點擊成效。
- **AI 摘要反應**：
  - 在任何包含討論串、長文或 YouTube 影片的訊息上，添加 `📝` Reaction，機器人將自動分析並回覆結構化摘要。
- **社群連結解析**：
  - 在頻道貼出 Threads, Bilibili, Reddit, YouTube, X, Instagram 連結，機器人將自動產生完整預覽卡片。
- **誰是臥底派對遊戲**：
  - `/undercover`：發起誰是臥底遊戲房間，等待玩家加入後發放詞彙並開始表決。
  - `/reveal`：揭曉場上所有玩家身分與詞彙。

---

### 2. 🌐 在 Web 控制台使用

1. 開啟瀏覽器造訪：`http://localhost:8080/login`
2. 點擊 **「🎮 使用 Discord 帳號一鍵登入」** 完成授權。
3. 登入後將自動跳轉至 Dashboard (`/dashboard`)：
   - **建立短網址**：在表單輸入長網址，點擊生成。
   - **複製短網址**：點擊表格中的「複製」按鈕。
   - **檢視詳細分析**：點擊「📊 分析細節」查看每日統計、來源網站、裝置與點擊日誌。
   - **刪除短網址**：點擊「🗑️ 刪除」移除短鏈接。

---

## 📁 專案結構與 Monorepo 架構 (Monorepo Architecture & Structure)

Luna 採用 Gradle 多專案 Monorepo 架構，整合核心應用程式、外掛子系統與獨立解析庫：

### 📦 模組說明 (Modules)

1. **Luna 根應用程式 (`:`)**
   - **用途**：Luna 核心主程式與外掛容器 (Application version: `2.1.0`, group: `luna`)。
   - **進入點**：`luna.core.MainKt`。
   - **架構**：
     - `core/`：Luna Host 生命週期、Ktor 伺服器啟動、Kord 機器人初始化、資料庫連線池與結構化日誌。
     - `plugin/`：`LunaPlugin` SPI 介面、`PluginContext` 注入與 `PluginLoader` 探索機制。
     - `curtly/`：短網址引擎、Ktor 路由與 Discord 指令適配器。
     - `preview/`：社群預覽外掛與伺服器設定儲存。
     - `summarizer/`：Gemini 多模態摘要管道、雙邊界回溯與雜訊過濾。
     - `undercover/`：誰是臥底派對遊戲邏輯與互動式表決處理器。

2. **SocialPeek 社群貼文解析模組 (`:libs:socialpeek`)**
   - **用途**：純 Kotlin 多平台社群媒體貼文與中繼資料解析庫 (Library version: `1.0.0`, group: `com.github.0oWoodenDooro0.Luna`)。
   - **功能**：支援 Threads、Bilibili、Reddit、YouTube、Facebook、X (Twitter)、Instagram 與 Generic Web 解析。
   - **發布**：支援獨立發布 MavenPublication (`socialpeek`)。

3. **集中式版本目錄 (Version Catalog)**
   - **位置**：`gradle/libs.versions.toml`。
   - **功能**：統一管理根專案與所有模組之依賴套件版本與 Gradle 外掛。

### 📂 目錄結構

```text
Luna/
├── .github/workflows/
│   ├── ci.yml               # GitHub Actions CI 工作流 (單元測試、程式碼規範、Maven 發布驗證)
│   └── docker-publish.yml   # GitHub Actions 自動構建與發布 Docker 映像檔
├── gradle/
│   ├── libs.versions.toml   # 集中式 Gradle Version Catalog 依賴版本目錄
│   └── wrapper/             # 根目錄 Gradle Wrapper
├── libs/
│   └── socialpeek/          # SocialPeek 社群貼文解析模組 (:libs:socialpeek)
│       ├── src/             # 模組原始碼與單元測試 (75 tests)
│       ├── build.gradle.kts # 模組構建與 Maven 發布設定
│       └── README.md        # SocialPeek 模組說明文件
├── src/main/kotlin/luna/
│   ├── core/                # Luna Host 基礎設施 (Main, Config, Database, JsonLogger, UserStorage)
│   ├── plugin/              # 外掛系統核心 (LunaPlugin, PluginContext, PluginLoader)
│   ├── curtly/              # Curtly 短網址外掛 (CurtlyPlugin, Routing, Engine, Storage)
│   ├── preview/             # Social Preview 外掛 (SocialPreviewPlugin, SettingsStorage)
│   ├── summarizer/          # AI 摘要外掛 (SummarizerPlugin, Gemini, NoiseFilter, AdaptiveVideo)
│   └── undercover/          # 誰是臥底遊戲外掛 (UndercoverPlugin, UndercoverManager, Commands)
├── src/main/resources/
│   └── META-INF/services/   # Java ServiceLoader SPI 外掛宣告檔 (luna.plugin.LunaPlugin)
├── src/test/kotlin/luna/    # 根應用與各外掛測試套件 (128 tests)
├── build.gradle.kts         # 根專案 Gradle 設定檔
├── settings.gradle.kts      # 多專案設定與子模組包含設定
├── Dockerfile               # 多階段輕量化 Docker 構建檔
├── docker-compose.yml       # 一鍵容器編排設定檔
├── GLOSSARY.md              # 領域模型與術語規格文件
└── README.md                # 專案說明文件
```

---

## 📄 授權條款 (License)

本專案採用 MIT 授權條款。詳細資訊請參閱 LICENSE 檔案。
