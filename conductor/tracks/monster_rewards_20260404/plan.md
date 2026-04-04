# Implementation Plan: Monster Rewards & Configuration Centralization

## Phase 1: Centralize Configuration in `RpgConfig` [checkpoint: e4be318]
- [x] Task: Reorganize `RpgConfig.kt` into categorized data structures. 66e7b57
    - [x] Create `Exploration` object for event rates and room logic.
    - [x] Create `Monster` object for base stats and scaling formulas.
    - [x] Create `Economy` object for resource rewards and upgrade costs.
    - [x] Create `Combat` object for turn limits and other combat parameters.
- [x] Task: Move all hardcoded numeric constants from `ExploreCommand.kt` and `CombatEngine.kt` to `RpgConfig.kt`. 2a0f322
- [x] Task: Update `ExploreCommand.kt` and `CombatEngine.kt` to use the new `RpgConfig` constants. b0fe3ac
- [~] Task: Conductor - User Manual Verification 'Phase 1: Configuration' (Protocol in workflow.md)

## Phase 2: Implement Monster Defeat Rewards
- [ ] Task: Implement reward calculation logic in `PlayerRepository.kt` or a new utility.
    - [ ] Add `calculateMonsterReward(floor: Int): Pair<String, Int>` that returns resource name and amount based on floor scaling in `RpgConfig`.
- [ ] Task: Update `ExploreCommand.handleCombat` to give rewards on victory.
    - [ ] Call the reward calculation upon victory.
    - [ ] Update player's inventory in the database.
    - [ ] Update the victory embed message to include reward information.
- [ ] Task: Add unit tests for the reward system in `RpgCoreTest.kt` or a new test file.
    - [ ] Test reward scaling based on floor level.
    - [ ] Test that resources are correctly added to the database.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Rewards' (Protocol in workflow.md)

## Phase 3: Final Integration & Verification
- [ ] Task: Verify all configuration parameters are correctly applied in the game.
    - [ ] Change a few values in `RpgConfig` (e.g., turn limit, floor size) and ensure they reflect in gameplay/tests.
- [ ] Task: End-to-end manual testing of the exploration and combat reward flow.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Final Verification' (Protocol in workflow.md)
