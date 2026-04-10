# Implementation Plan: Scale Exploration Supplies by Floor Level

## Phase 1: Configuration Updates
- [ ] Task: Update `RpgConfigLoader.kt` to include `resource_scale_per_floor` in `ExplorationConfig`.
- [ ] Task: Update `RpgConfig.kt` to expose `RESOURCE_SCALE_PER_FLOOR`.
- [ ] Task: Update default `config.yml` generation to include the new setting.
- [ ] Task: Conductor - User Manual Verification 'Configuration Updates' (Protocol in workflow.md)

## Phase 2: Logic Implementation & Refactoring
- [ ] Task: Add `calculateExplorationReward(floor: Int, player: Player? = null)` to `PlayerRepository.kt`.
- [ ] Task: Refactor `ExploreCommand.kt` to use `PlayerRepository.calculateExplorationReward`.
- [ ] Task: Conductor - User Manual Verification 'Logic Implementation & Refactoring' (Protocol in workflow.md)

## Phase 3: Verification & Testing
- [ ] Task: Create `ExplorationScalingTest.kt` to verify scaling logic across different floors (Floor 1, Floor 10, etc.).
- [ ] Task: Run all tests to ensure no regressions.
- [ ] Task: Conductor - User Manual Verification 'Verification & Testing' (Protocol in workflow.md)
