# Implementation Plan: /map Command & Dungeon Map System

## Phase 1: Database and Model Foundation [checkpoint: 19c009c]
- [x] Task: Create `PlayerMap` model and `PlayerMapsTable` in `DatabaseManager.kt` (6f9309a)
    - [ ] Define `PlayerMapsTable` with fields: `id`, `playerId`, `layer`, `dropRate`, `rooms` (fixed 20), `currentRoom`, `isActive`.
    - [ ] Create `PlayerMap` data class.
- [x] Task: Implement `PlayerMapRepository` for database operations (7d2a429)
    - [ ] `createMap(playerId, layer, dropRate)`: Insert new map and deduct resources.
    - [ ] `getMaps(playerId)`: Retrieve all maps for a player.
    - [ ] `getActiveMap(playerId)`: Retrieve the active map for a player.
    - [ ] `setActiveMap(playerId, mapId)`: Set a map as active (and deactivate others).
    - [ ] `updateProgress(mapId, currentRoom)`: Update the current room in a map.
    - [ ] `deleteMap(playerId, mapId)`: Delete a specific map.
- [x] Task: Write tests for `PlayerMapRepository` and `PlayerMapsTable` (7d2a429)
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Database and Model Foundation' (Protocol in workflow.md)

## Phase 2: Core Logic and Cost Calculation
- [ ] Task: Implement map creation cost calculation in `RpgConfig`
    - [ ] Define cost formula based on `dropRate` and `layer`.
    - [ ] Add configuration for base costs in `config.yml`.
- [ ] Task: Implement `MapService` for business logic
    - [ ] Handle map creation validation (resources, drop rate).
    - [ ] Handle resource deduction.
- [ ] Task: Write tests for cost calculation and `MapService` logic
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Core Logic and Cost Calculation' (Protocol in workflow.md)

## Phase 3: Integration with /explore Command
- [ ] Task: Modify `CombatEngine` and `ExploreCommand` to use active maps
    - [ ] Check for active map when `/explore` is called.
    - [ ] If map active: use map's `layer` and `dropRate`.
    - [ ] If map active: progress through map's 20 rooms.
    - [ ] If map active: handle map completion (restart or reset).
- [ ] Task: Disable standard auto-advance when a map is active
- [ ] Task: Write tests for `ExploreCommand` map integration
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Integration with /explore Command' (Protocol in workflow.md)

## Phase 4: /map Discord Commands
- [ ] Task: Implement `/map create` command
- [ ] Task: Implement `/map list` command
- [ ] Task: Implement `/map select` command
- [ ] Task: Implement `/map delete` command
- [ ] Task: Register new commands in `Main.kt`
- [ ] Task: Write integration tests for new commands
- [ ] Task: Conductor - User Manual Verification 'Phase 4: /map Discord Commands' (Protocol in workflow.md)
