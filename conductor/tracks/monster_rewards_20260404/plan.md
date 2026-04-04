# Implementation Plan: Monster Rewards & Configuration Centralization

## Phase 1: Centralize Configuration in `RpgConfig` [checkpoint: e4be318]
- [x] Task: Reorganize `RpgConfig.kt` into categorized data structures. 66e7b57
    - [x] Create `Exploration` object for event rates and room logic.
    - [x] Create `Monster` object for base stats and scaling formulas.
    - [x] Create `Economy` object for resource rewards and upgrade costs.
    - [x] Create `Combat` object for turn limits and other combat parameters.
- [x] Task: Move all hardcoded numeric constants from `ExploreCommand.kt` and `CombatEngine.kt` to `RpgConfig.kt`. 2a0f322
- [x] Task: Update `ExploreCommand.kt` and `CombatEngine.kt` to use the new `RpgConfig` constants. b0fe3ac
- [x] Task: Conductor - User Manual Verification 'Phase 1: Configuration' (Protocol in workflow.md) e4be318

## Phase 2: Implement Monster Defeat Rewards [checkpoint: 33c9d40]
- [x] Task: Implement reward calculation logic in `PlayerRepository.kt` or a new utility. d069cdf
    - [x] Add `calculateMonsterReward(floor: Int): Pair<String, Int>` that returns resource name and amount based on floor scaling in `RpgConfig`.
- [x] Task: Update `ExploreCommand.handleCombat` to give rewards on victory. ea0400b
    - [x] Call the reward calculation upon victory.
    - [x] Update player's inventory in the database.
    - [x] Update the victory embed message to include reward information.
- [x] Task: Add unit tests for the reward system in `RpgCoreTest.kt` or a new test file. 0db28df
    - [x] Test reward scaling based on floor level.
    - [x] Test that resources are correctly added to the database.
- [x] Task: Conductor - User Manual Verification 'Phase 2: Rewards' (Protocol in workflow.md) 33c9d40

## Phase 3: Final Integration & Verification [checkpoint: c6ae74f]
- [x] Task: Verify all configuration parameters are correctly applied in the game. [verified]
    - [x] Change a few values in `RpgConfig` (e.g., turn limit, floor size) and ensure they reflect in gameplay/tests.
- [x] Task: End-to-end manual testing of the exploration and combat reward flow. c6ae74f
- [x] Task: Conductor - User Manual Verification 'Phase 3: Final Verification' (Protocol in workflow.md) c6ae74f

## Phase: Review Fixes
- [x] Task: Apply review suggestions 350b833
