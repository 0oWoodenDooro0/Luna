# Implementation Plan: Rebirth Mechanism

## Phase 1: Configuration & Database Models [checkpoint: 14ad429]
- [x] Task: Write tests for `RebirthConfig` parsing and default values. c639fe4
- [x] Task: Update `RpgConfig` with nested `RebirthConfig` (minLevel, milestones, upgrade costs, max caps). 1cd570f
- [x] Task: Write tests for `PlayerRepository` to verify saving and loading of rebirth fields. 073b012
- [x] Task: Update `PlayerRepository` and `PlayersTable` (Exposed ORM) to persist rebirth points and upgraded stats. 073b012
    - [x] Update `PlayersTable` with new columns for rebirth points, and stat levels (ATK, DEF, SPD, Recovery, HP).
    - [x] Update `PlayerRepository` to map new columns.
- [x] Task: Conductor - User Manual Verification 'Configuration & Database Models' (Protocol in workflow.md)

## Phase 2: Core Rebirth Logic [checkpoint: 8f8e45b]
- [x] Task: Write tests for Rebirth logic (calculating points based on milestones, validating minimum level). 3cd7045
- [x] Task: Write tests for Hard Reset logic (resetting level, HP, equipment, materials). 3cd7045
- [x] Task: Implement Rebirth logic in `RpgModels` or a new `RebirthEngine`. 3cd7045
- [x] Task: Ensure the Rebirth logic correctly updates the player model and persists it via `PlayerRepository`. 3cd7045
- [x] Task: Conductor - User Manual Verification 'Core Rebirth Logic' (Protocol in workflow.md)

## Phase 3: Stat Upgrade Mechanics
- [x] Task: Write tests for stat upgrade logic (verifying progressive costs, checking max caps, point deduction). 426ea40
- [x] Task: Write tests for applying upgraded stat percentage bonuses during combat calculations and status display. 426ea40
- [x] Task: Implement stat upgrade logic (handling point spending and caps). 426ea40
- [x] Task: Integrate percentage bonuses into `CombatEngine` and `RpgModels` (effective stat calculation). 426ea40
- [~] Task: Conductor - User Manual Verification 'Stat Upgrade Mechanics' (Protocol in workflow.md)

## Phase 4: Discord Commands Integration
- [ ] Task: Create tests for `/rebirth` command handling.
- [ ] Task: Create tests for `/rebirth_upgrade` command handling.
- [ ] Task: Implement `/rebirth` Discord command (showing rebirth info, confirming, and executing rebirth).
- [ ] Task: Implement `/rebirth_upgrade` Discord command (showing available points, upgrade costs, current levels, and allowing selection of stat to upgrade).
- [ ] Task: Update `/status` command to display rebirth count, current points, and upgraded stat bonuses.
- [ ] Task: Conductor - User Manual Verification 'Discord Commands Integration' (Protocol in workflow.md)