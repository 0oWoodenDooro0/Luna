# Implementation Plan: Death Recovery & Monster Persistence

## Phase 1: Database & Model Preparation [checkpoint: 5a6a41b]
- [x] Task: Update `PlayersTable` and `PlayerRepository` to include monster persistence fields. a24a52b
    - [x] Add columns for monster's current state (HP, ATK, DEF, etc.) to the database.
    - [x] Create a `MonsterState` model or embed fields into the player's exploration state.
- [x] Task: Implement monster state saving/loading logic in `PlayerRepository`. a24a52b
    - [x] Add `saveMonsterState(playerId, monster)` method.
    - [x] Add `loadMonsterState(playerId)` method.
- [x] Task: Conductor - User Manual Verification 'Phase 1: Database' (Protocol in workflow.md) 5a6a41b

## Phase 2: Death Logic Refinement
- [ ] Task: Modify the death handling in `ExploreCommand` (or relevant logic).
    - [ ] When player dies, save the monster's current state instead of clearing the room.
    - [ ] Ensure the player remains in the current room.
    - [ ] Add unit tests for death state saving in `RecoveryLogicTest`.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Death Logic' (Protocol in workflow.md)

## Phase 3: Fight Resumption Logic
- [ ] Task: Update `ExploreCommand` to handle fight resumption.
    - [ ] Add a check at the beginning of `ExploreCommand` to see if a monster state is saved.
    - [ ] Implement the "Reviving" check: if player is not at full health, block `explore` and show message.
    - [ ] If player is at full health and a monster is saved, resume the fight using the loaded monster state.
    - [ ] Add unit tests for fight resumption in `RpgCoreTest`.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Resumption Logic' (Protocol in workflow.md)

## Phase 4: Final Verification
- [ ] Task: End-to-end manual testing.
    - [ ] Verify death does not end exploration.
    - [ ] Verify monster state is persisted.
    - [ ] Verify `explore` works correctly during revival and after full health.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Final Verification' (Protocol in workflow.md)
