# Specification: Death Cooldown & Automatic Recovery (Configurable)

## Overview
Players who reach 0 HP during exploration will enter a "Recovery" cooldown. During this period, the `/explore` command is disabled. The cooldown duration is calculated based on the player's maximum HP and reduced by a new "Recovery Speed" upgrade. All parameters are defined in `RpgConfig.kt`.

## Functional Requirements
- **Death Trigger:** When a player loses combat in `ExploreCommand`, their HP is set to 0 and a "Recovery Start" timestamp is recorded in the database.
- **Configurable Parameters (`RpgConfig.kt`):**
    - `RECOVERY_BASE_SECONDS_PER_HP`: Base cooldown seconds per unit of Max HP (Default: 0.1, i.e., 1s per 10 HP).
    - `RECOVERY_UPGRADE_REDUCTION_SECONDS`: Seconds reduced per level of "Recovery Speed" (Default: 5.0).
    - `RECOVERY_MIN_SECONDS`: Minimum recovery duration (Default: 5.0).
    - `RECOVERY_UPGRADE_COST`: Resource cost map for the new upgrade.
- **Cooldown Calculation:**
    - `Base = MaxHP * RECOVERY_BASE_SECONDS_PER_HP`
    - `Actual = Max(RECOVERY_MIN_SECONDS, Base - (RecoveryLevel * RECOVERY_UPGRADE_REDUCTION_SECONDS))`
- **Explore Command Restriction:**
    - If `currentTime < recoveryStartTime + actualCooldown`, prevent exploration.
    - Show error message: "❤️ 你正在康復中... 剩餘時間: {remaining} 秒。"
- **Automatic Restoration:**
    - When any command (like `/status` or `/explore`) is run after the cooldown has expired, if the player's HP is 0, it is automatically restored to `maxHp`.
- **New Upgrade:**
    - Name: "Recovery Speed" (康復速度).
    - Added to `UpgradeCommand`.

## Technical Changes
- **Database:** Add `recovery_start_at` (Long/Timestamp) and `recovery_level` (Int) to `PlayersTable`.
- **RpgConfig:** Add the new constants.
- **Logic:** Update `ExploreCommand` combat loop and `PlayerRepository` to handle restoration logic.

## Acceptance Criteria
- Player dies -> HP set to 0, recovery starts.
- `/explore` during cooldown -> Shows countdown.
- Cooldown expires -> Next command restores HP and allows exploration.
- Upgrading "Recovery Speed" reduces the cooldown correctly.
- Changing `RpgConfig` values immediately affects the calculation.