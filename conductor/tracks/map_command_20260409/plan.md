# Implementation Plan: /map Command & Dungeon Map System

## Phase 1: Database and Model Foundation [checkpoint: 19c009c]
- [x] Task: Create `PlayerMap` model and `PlayerMapsTable` in `DatabaseManager.kt` (6f9309a)
    - [x] Define `PlayerMapsTable` with fields: `id`, `playerId`, `layer`, `dropRate`, `rooms` (fixed 20), `currentRoom`, `isActive`.
    - [x] Create `PlayerMap` data class.
- [x] Task: Implement `PlayerMapRepository` for database operations (7d2a429)
    - [x] `createMap(playerId, layer, dropRate)`: Insert new map and deduct resources.
    - [x] `getMaps(playerId)`: Retrieve all maps for a player.
    - [x] `getActiveMap(playerId)`: Retrieve the active map for a player.
    - [x] `setActiveMap(playerId, mapId)`: Set a map as active (and deactivate others).
    - [x] `updateProgress(mapId, currentRoom)`: Update the current room in a map.
    - [x] `deleteMap(playerId, mapId)`: Delete a specific map.
- [x] Task: Write tests for `PlayerMapRepository` and `PlayerMapsTable` (7d2a429)
- [x] Task: Conductor - User Manual Verification 'Phase 1: Database and Model Foundation' (Protocol in workflow.md)

## Phase 2: Core Logic and Cost Calculation [checkpoint: b16d7d4]
- [x] Task: Implement map creation cost calculation in `RpgConfig` (00e139f)
    - [x] Define cost formula based on `dropRate` and `layer`.
    - [x] Add configuration for base costs in `config.yml`.
- [x] Task: Implement `MapService` for business logic (9bcea85)
    - [x] Handle map creation validation (resources, drop rate).
    - [x] Handle resource deduction.
- [x] Task: Write tests for cost calculation and `MapService` logic (9bcea85)
- [x] Task: Conductor - User Manual Verification 'Phase 2: Core Logic and Cost Calculation' (Protocol in workflow.md)

## Phase 3: Integration with /explore Command
- [x] Task: Modify `CombatEngine` and `ExploreCommand` to use active maps (e924622)
    - [x] Check for active map when `/explore` is called.
    - [x] If map active: use map's `layer` and `dropRate`.
    - [x] If map active: progress through map's 20 rooms.
    - [x] If map active: handle map completion (restart or reset).
- [x] Task: Disable standard auto-advance when a map is active (e924622)
- [x] Task: Write tests for `ExploreCommand` map integration (e924622)
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Integration with /explore Command' (Protocol in workflow.md)

## Phase 4: /map Discord Commands
- [ ] Task: Implement `/map create` command
- [ ] Task: Implement `/map list` command
- [ ] Task: Implement `/map select` command
- [ ] Task: Implement `/map delete` command
- [ ] Task: Register new commands in `Main.kt`
- [ ] Task: Write integration tests for new commands
- [ ] Task: Conductor - User Manual Verification 'Phase 4: /map Discord Commands' (Protocol in workflow.md)
