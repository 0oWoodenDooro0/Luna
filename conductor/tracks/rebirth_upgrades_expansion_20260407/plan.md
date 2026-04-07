# Implementation Plan: rebirth_upgrades_expansion_20260407

## Phase 1: Configuration & Models [checkpoint: 491c363]
- [x] Task: Update `RpgConfigData` and `RpgConfigLoader` to include new rebirth settings. 24bec6a
- [x] Task: Update `RpgConfig` object to provide access to new settings. c4af28d
- [x] Task: Update `Player` data class in `RpgModels.kt` to include `rebirthResourceLevel` and `rebirthEfficientLevel`. eb71cd5
- [x] Task: Conductor - User Manual Verification 'Phase 1: Configuration & Models' (Protocol in workflow.md) 491c363

## Phase 2: Database Persistence [checkpoint: 3e6554a]
- [x] Task: Update `PlayersTable` in `repository/PlayersTable.kt` with new columns. 860c5cb
- [x] Task: Update `PlayerRepository.kt` to handle loading and saving new rebirth stats. cbe012e
- [x] Task: Update `rebirthUpgrade` method in `PlayerRepository.kt` to handle the new upgrades. cbe012e
- [x] Task: Write tests in `RebirthPersistenceTest.kt` for new database fields. 5a1f611
- [x] Task: Conductor - User Manual Verification 'Phase 2: Database Persistence' (Protocol in workflow.md) 3e6554a

## Phase 3: Core Logic (Bonuses)
- [ ] Task: Update `CombatEngine.kt` to apply "Resourceful" bonus to monster rewards.
- [ ] Task: Update `ExploreCommand.kt` to apply "Resourceful" bonus to found resources.
- [ ] Task: Update `UpgradeCommand.kt` to apply "Efficient" bonus to upgrade costs.
- [ ] Task: Write unit tests for rebirth bonus logic in `RpgCoreTest.kt` or new `RebirthBonusTest.kt`.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Core Logic (Bonuses)' (Protocol in workflow.md)

## Phase 4: User Interface (Commands)
- [ ] Task: Update `RebirthListCommand.kt` to display new upgrades and their current levels/effects.
- [ ] Task: Update `RebirthUpgradeCommand.kt` to support upgrading "Resourceful" and "Efficient".
- [ ] Task: Update `StatusCommand.kt` to reflect adjusted stats if necessary.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: User Interface (Commands)' (Protocol in workflow.md)

## Phase 5: Final Review & Integration
- [ ] Task: Run full test suite and linting.
- [ ] Task: Verify everything works together as expected.
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Final Review & Integration' (Protocol in workflow.md)
