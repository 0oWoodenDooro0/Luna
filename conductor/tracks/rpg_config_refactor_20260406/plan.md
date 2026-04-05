# Implementation Plan: RPG Config Refactor

## Phase 1: Preparation and Environment (準備階段) [checkpoint: 98af24d]
- [x] Task: 分析現有的 `RpgConfig.kt` 結構，確定哪些屬性需要外部化。 (2aa2c27)
- [x] Task: 選擇或添加 YAML 處理庫（如 SnakeYAML 或 Kotlin-specific libraries）。 (2aa2c27)
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md) (98af24d)

## Phase 2: Configuration Loader Implementation (實作配置讀取) [checkpoint: 2db905e]
- [x] Task: 建立 `RpgConfigLoader` 類別，負責讀取並解析 `config.yml`。 (6bbdb12)
- [x] Task: 實作自動生成預設 `config.yml` 的邏輯。 (6bbdb12)
- [x] Task: 寫測試案例：驗證當 `config.yml` 不存在時，會自動生成預設值。 (6bbdb12)
- [x] Task: 寫測試案例：驗證當 `config.yml` 存在且包含自定義數值時，Loader 會讀取自定義值。 (6bbdb12)
- [x] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md) (2db905e)

## Phase 3: Integration and Reload Logic (整合與重載邏輯) [checkpoint: ce4e581]
- [x] Task: 修改 `RpgConfig` 以便它使用 `RpgConfigLoader` 的實例。 (eb01958)
- [x] Task: 實作 `/reload` 指令，調用 `RpgConfigLoader` 的 reload 方法。 (eb01958)
- [x] Task: 寫測試案例：驗證執行 `/reload` 後，內存中的配置已成功更新。 (eb01958)
- [x] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md) (ce4e581)

## Phase 4: Final Verification and Cleanup (最終驗證與清理)
- [ ] Task: 在開發環境中模擬 JAR 打包，手動測試 `config.yml` 的生成、修改與重載。
- [ ] Task: 清理無用的硬編碼配置。
- [ ] Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md)
