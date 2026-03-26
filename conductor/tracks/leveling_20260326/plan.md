# Implementation Plan: Player Leveling and Experience System

## Phase 1: Data Model & Persistence [checkpoint: 7559533]
- [x] Task: Update the `Player` table schema to include `xp` and `level` columns. 26fe7fc
    - [x] Write tests for database schema migration and player data retrieval.
    - [x] Implement the schema changes and update the `PlayerRepository`.
- [x] Task: Implement XP and Level update methods in `PlayerRepository`. 14dc257
    - [x] Write tests for updating XP and level fields in the database.
    - [x] Implement `addXp` and `setLevel` methods in `PlayerRepository`.
- [x] Task: Conductor - User Manual Verification 'Phase 1: Data Model & Persistence' (Protocol in workflow.md) 7559533

## Phase 2: Core Leveling Logic
- [x] Task: Implement XP threshold and level-up calculation logic. 204eab9
    - [x] Write unit tests for the level-up formula and threshold calculations.
    - [x] Implement the logic in a new `LevelingService` or within the `Player` model.
- [ ] Task: Integrate leveling logic with XP gain.
    - [ ] Write tests for the automatic level-up when XP is added.
    - [ ] Update the `addXp` logic to trigger level-up checks and updates.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Core Leveling Logic' (Protocol in workflow.md)

## Phase 3: Integration with Hunt Command
- [ ] Task: Award XP upon successful hunt completion.
    - [ ] Write integration tests for the `HuntCommand` to verify XP is awarded.
    - [ ] Update `HuntCommand` to call the XP awarding logic after a hunt.
- [ ] Task: Update Discord feedback for XP gains and level-ups.
    - [ ] Write tests for generating the correct Discord message content.
    - [ ] Update the `HuntCommand` response to include XP gain and level-up notifications.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Integration with Hunt Command' (Protocol in workflow.md)
