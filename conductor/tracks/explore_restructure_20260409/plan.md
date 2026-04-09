# Implementation Plan: Explore Command Restructure & Settings Removal

## Phase 1: Core Logic & Database Enforcement [checkpoint: afe6fc2]
- [x] Task: Modify `PlayerRepository.updateProgression` to ignore the `auto_advance` setting and always use `true`. 26f4515
    - [x] Update `PlayerRepository.kt`.
    - [x] Write unit tests to verify that progression always advances to the next floor upon completion.
- [x] Task: Conductor - User Manual Verification 'Phase 1: Core Logic' (Protocol in workflow.md) afe6fc2

## Phase 2: Command Cleanup (Settings Removal) [checkpoint: 1251c5a]
- [x] Task: Remove `SettingsCommand` from the project. d260ea2
    - [x] Delete `src/main/kotlin/luna/rpg/command/SettingsCommand.kt`.
    - [x] Remove `SettingsCommand()` registration in `src/main/kotlin/luna/core/Main.kt`.
    - [x] Clean up any related tests or references.
- [x] Task: Conductor - User Manual Verification 'Phase 2: Command Cleanup' (Protocol in workflow.md) 1251c5a

## Phase 3: Repurpose ExploreCommand (Main Floor Only) [checkpoint: 31f0589]
- [x] Task: Refactor `ExploreCommand.kt` to focus exclusively on main floor exploration. d880a27
    - [x] Remove `activeMap` logic and its use of `PlayerMapRepository`.
    - [x] Update response messages to indicate "Main Floor" (主要地層) exploration.
    - [x] Update `src/test/kotlin/luna/rpg/command/ExploreCommandMapTest.kt` to reflect the new behavior.
- [x] Task: Conductor - User Manual Verification 'Phase 3: Repurpose ExploreCommand' (Protocol in workflow.md) 31f0589

## Phase 4: Implement DungeonCommand (Map Exploration Only)
- [x] Task: Create and register the `DungeonCommand`. 8f277e9
    - [x] Create `src/main/kotlin/luna/rpg/command/DungeonCommand.kt` with logic for exploring the `activeMap`.
    - [x] Add error handling for when no map is active.
    - [x] Register `DungeonCommand` in `src/main/kotlin/luna/core/Main.kt`.
    - [x] Create `src/test/kotlin/luna/rpg/command/DungeonCommandTest.kt` with success and "no active map" cases.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Implement DungeonCommand' (Protocol in workflow.md)

## Phase 5: Final Verification & Integration
- [ ] Task: Run all project tests and quality checks.
    - [ ] Run `./gradlew test`.
    - [ ] Run `./gradlew lintKotlin`.
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Final Verification' (Protocol in workflow.md)
