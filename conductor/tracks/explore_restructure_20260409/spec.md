# Specification: Explore Command Restructure & Settings Removal

## 1. Overview
The goal of this track is to simplify and clarify the exploration experience by separating main floor exploration from custom map exploration. This involves repurposing the `/explore` command, creating a new `/dungeon` command, and removing the `/settings` command to enforce automatic floor progression.

## 2. Functional Requirements

### 2.1 Repurpose `/explore` Command
-   **Target**: The player's current main floor (`player.currentFloor`).
-   **Behavior**:
    -   Always perform exploration on the main floor.
    -   **Ignore** any active custom map (`activeMap`).
    -   Maintains existing combat and resource gathering logic.
-   **Output**: Clearly state that the exploration is occurring on the main floor.

### 2.2 Create `/dungeon` Command
-   **Target**: The player's currently active custom map (`activeMap`).
-   **Behavior**:
    -   If an active map is found, perform exploration on that map.
    -   If **no** active map is found, return an error message: "❌ 你目前沒有選中的地圖。請使用 `/map select <id>` 來選取一個地圖！"
    -   Maintains existing combat and resource gathering logic for maps (e.g., map-specific drop rates and room limits).
-   **Output**: Clearly state that the exploration is occurring in the selected map/dungeon.

### 2.3 Remove `/settings` Command
-   **Action**: Delete `SettingsCommand.kt` and its registration in `Main.kt`.
-   **Enforced Behavior**:
    -   The `auto_advance` functionality will be permanently enabled.
    -   Players will automatically advance to the next floor when they complete the required number of rooms on the main floor.

### 2.4 Update Progression Logic
-   Modify `PlayerRepository.updateProgression` to ignore the `auto_advance` preference from the database and always behave as if it is `true`.

## 3. Acceptance Criteria
-   Executing `/explore` results in exploration on the main floor, even if a map is selected.
-   Executing `/dungeon` results in exploration on the active map.
-   Executing `/dungeon` when no map is active displays a helpful error message.
-   The `/settings` command is removed and no longer appears in Discord.
-   Players automatically advance to the next floor when completing a floor's rooms.
-   All existing unit tests and new tests for `/dungeon` pass.

## 4. Out of Scope
-   Modifying the creation, listing, selection, or deletion of maps (`/map` command suite).
-   Changing the core combat engine or reward calculation logic.
-   Adding new exploration events or monsters.
