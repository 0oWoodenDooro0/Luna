# Specification: RPG Config Refactor

## Overview
這項 Track 的目標是將現有的 `RpgConfig`（目前可能硬編碼在 Kotlin 文件中）重構成外部配置文件（YAML 格式）。這將允許使用者在打包成 JAR 檔後，仍能透過修改外部文件來調整 RPG 遊戲的各項數值（如經驗值倍率、掉落率等），而無需重新編譯。

## Functional Requirements
1. **外部化配置**：將所有 RPG 相關數值（經驗倍率、掉落機率、等級上限等）從原始碼移至外部 `config.yml` 文件。
2. **YAML 格式支援**：使用 YAML 格式，提供清晰、可讀的結構。
3. **自動生成預設值**：當 `config.yml` 不存在時，程式應自動在根目錄生成一個包含預設值和範例的 `config.yml`。
4. **手動重載指令**：提供一個指令（例如 `/reload`），在修改配置文件後，無需重啟 JAR 即可重新讀取數值。
5. **JAR 外部可見性**：確保配置文件位於 JAR 檔所在的根目錄，而非打包在 JAR 內部。

## Non-Functional Requirements
1. **強健性**：如果 `config.yml` 格式錯誤，應提供清晰的錯誤訊息並使用最後一次成功的配置或預設值。
2. **易用性**：配置文件應包含註釋（Examples），解釋各項數值的意義。

## Acceptance Criteria
1. 打包成 JAR 後，首次運行時會在此目錄生成 `config.yml`。
2. 修改 `config.yml` 並執行重載指令後，遊戲內的數值會立即更新。
3. 刪除 `config.yml` 後，程式會自動恢復預設值（並再次生成文件）。

## Out of Scope
1. 實作自動偵測文件更動（Auto-reload on change）。
2. 提供網頁介面修改配置。
