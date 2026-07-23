# 🌙 Luna Curtly - 短網址與數據分析系統

Luna 是一款結合 **Discord 機器人** 與 **Ktor Web 控制台** 的現代化短網址服務。基於 [Curtly 3.1.0](https://github.com/0oWoodenDooro0/Curtly) 短網址引擎庫開發，支援 **Discord OAuth2 一鍵登入**、短網址個人化管理與多維度點擊數據分析。

---

## ✨ 主要特色

- 🎮 **Discord 機器人整合**：
  - `/shorten`：在 Discord 中直接將長網址轉為短網址（可選填自訂別名、有效時間與點擊限制）。
  - `/myurls`：隨時查看自己在該 Discord 帳號下建立的所有短網址與點擊次數。
- 🔑 **Discord OAuth2 一鍵登入**：
  - 控制台完全整合 Discord OAuth2，登入後網頁帳號與 Discord 帳號自動綁定。在 Discord 與網頁上生成的短鏈接全數同步管理。
- 📊 **多維度點擊分析**：
  - 即時統計總點擊數、每日點擊趨勢、熱門來源網站 (Top Referrers)、熱門裝置/瀏覽器 (User-Agent)、點擊 IP 來源與詳細時間點擊日誌。
- 🎨 **現代化玻璃擬態 Dashboard**：
  - 極簡暗色系 Web 介面，支援一鍵複製短網址、即時搜尋、點擊分析彈窗與鏈接刪除。
- ⚡ **高效能與 SQLite 持久化**：
  - 使用 Kotlin + Ktor + Exposed (SQLite) 打造，輕量高效。

---

## 🛠️ 環境變數設定 (Environment Variables)

啟動 Luna 之前，請設定以下環境變數（或於 `application.yml` 中設定）：

| 環境變數 | 說明 | 必填 / 範例 |
| :--- | :--- | :--- |
| `DISCORD_TOKEN` | Discord 機器人 Token | **必填** (如：`MTEyM...`) |
| `DISCORD_CLIENT_ID` | Discord Application ID (OAuth2 登入用) | **必填** (如：`123456789012345678`) |
| `DISCORD_CLIENT_SECRET` | Discord Client Secret (OAuth2 登入用) | **必填** (如：`xxxxxx`) |
| `DISCORD_REDIRECT_URI` | OAuth2 重導向網址 | 選填 (預設：`http://localhost:8080/api/auth/discord/callback`) |
| `BASE_URL` | 短網址基底網域 | 選填 (預設：`http://localhost:8080/s/`) |
| `PORT` | Web 伺服器通訊埠 | 選填 (預設：`8080`) |

---

## ⚙️ Discord 開發者後台設定步驟

1. 前往 [Discord Developer Portal](https://discord.com/developers/applications) 並點選您的 Application。
2. **取得 Client ID**：在 `General Information` 頁面複製 `APPLICATION ID`（即 `DISCORD_CLIENT_ID`）。
3. **取得 Client Secret**：前往 `OAuth2` -> `General` 頁面，點擊 `Reset Secret` 取得 `DISCORD_CLIENT_SECRET`。
4. **設定 Redirects**：在 `OAuth2` -> `General` 的 `Redirects` 區塊添加：
   - 本地測試：`http://localhost:8080/api/auth/discord/callback`
   - 點擊右下角 `Save Changes` 儲存變更。

---

## 🚀 快速開始與專案執行

### 環境要求
- Java JDK 17 或以上

### 1. 複製專案與建置
```bash
git clone https://github.com/0oWoodenDooro0/Luna.git
cd Luna
./gradlew build
```

### 2. 設定環境變數並啟動
```bash
export DISCORD_TOKEN="你的_DISCORD_TOKEN"
export DISCORD_CLIENT_ID="你的_DISCORD_CLIENT_ID"
export DISCORD_CLIENT_SECRET="你的_DISCORD_CLIENT_SECRET"
export BASE_URL="http://localhost:8080/s/"

./gradlew run
```

---

## 📖 使用方法 (Usage)

### 1. 🤖 在 Discord 使用

在已加入機器人的 Discord 頻道中輸入斜線指令：

- **建立短網址**：
  ```text
  /shorten url:https://example.com/very-long-url custom_key:my-link expires_in:86400 max_clicks:100
  ```
  *(僅 `url` 為必填，其餘為選填。未填寫 `custom_key` 時會自動生成 6 位隨機別名)*

- **查看個人短網址清單**：
  ```text
  /myurls
  ```

---

### 2. 🌐 在 Web 控制台使用

1. 開啟瀏覽器造訪：`http://localhost:8080/login`
2. 點擊 **「🎮 使用 Discord 帳號一鍵登入」** 完成授權。
3. 登入後將自動跳轉至 Dashboard (`/dashboard`)：
   - **建立短網址**：在上方的表單輸入長網址，點擊生成。
   - **複製短網址**：點擊表格中的「複製」按鈕。
   - **檢視詳細分析**：點擊表格中的「📊 分析細節」查看每日統計、來源網站、裝置與點擊日誌。
   - **刪除短網址**：點擊表格中的「🗑️ 刪除」移除短鏈接。

---

## 📂 專案結構 (Project Structure)

```text
Luna/
├── src/main/kotlin/luna/core/
│   ├── Main.kt              # 應用程式進入點與 Ktor/Kord 初始化
│   ├── CurtlyRouting.kt     # Web 路由、API Endpoints 與 Discord OAuth2 控制
│   ├── LunaWebPages.kt      # 前端 HTML/CSS/JS 控制台與登入頁面渲染
│   ├── UserStorage.kt       # 使用者資料庫與 Exposed Table 定義
│   ├── ShortenCommand.kt    # Discord Slash Command: /shorten
│   ├── MyUrlsCommand.kt     # Discord Slash Command: /myurls
│   └── JsonLogger.kt        # JSON 結構化日誌記錄器
├── build.gradle.kts         # Gradle 專案設定檔
└── README.md                # 專案說明文件
```

---

## 📄 授權條款 (License)

本專案採用 MIT 授權條款。詳細資訊請參閱 LICENSE 檔案。
