# Plan: Death Cooldown & Automatic Recovery

## Phase 1: Configuration & Database Persistence [checkpoint: d7af543]
- [x] Task: Add configurable parameters for recovery in `RpgConfig.kt`. 529fd3f
- [x] Task: Update `PlayersTable` schema to include `recovery_start_at` (Long) and `recovery_level` (Int). dc66eee
- [x] Task: Update `PlayerData` model in `RpgModels.kt` to include the new fields. 212053e
- [x] Task: Conductor - User Manual Verification 'Configuration & Database Persistence' (Protocol in workflow.md) d7af543

## Phase 2: Core Recovery Logic [checkpoint: 97e126a]
- [x] Task: Implement `calculateRecoveryCooldown(maxHp, recoveryLevel)` function in `RpgConfig.kt` or a service class. 710cf0f
- [x] Task: Create `isRecovering(player)` and `restoreHpIfRecovered(player)` functions in `PlayerRepository`. ebb4a92
- [x] Task: Update `ExploreCommand` to trigger the recovery cooldown when a player dies in combat (0 HP). 78595ec
- [x] Task: Update `ExploreCommand` to check `isRecovering` and prevent exploration with a timer message. 78595ec
- [x] Task: Update `StatusCommand` and `ExploreCommand` to call `restoreHpIfRecovered` before processing. 78595ec
- [x] Task: Conductor - User Manual Verification 'Core Recovery Logic' (Protocol in workflow.md) 97e126a

## Phase 3: Recovery Speed Upgrade [checkpoint: e2e027f]
- [x] Task: Add "Recovery Speed" (康復速度) to the `UpgradeCommand` list. a8f721a
- [x] Task: Implement the upgrade logic for "Recovery Speed" in `PlayerRepository`. 49e1c32
- [x] Task: Verify the upgrade reduces the cooldown correctly in subsequent combats. 49e1c32
- [x] Task: Conductor - User Manual Verification 'Recovery Speed Upgrade' (Protocol in workflow.md) e2e027f

## Phase 4: Final Integration & Polish [checkpoint: 52f376f]
- [x] Task: Run full regression tests to ensure no breakage in `ExploreCommand` or `UpgradeCommand`. 1d30338
- [x] Task: Final code style review and documentation updates. 1d30338
- [x] Task: Conductor - User Manual Verification 'Final Integration & Polish' (Protocol in workflow.md) 52f376f

---
*Note: Each task will follow the TDD workflow (Red -> Green -> Refactor).*