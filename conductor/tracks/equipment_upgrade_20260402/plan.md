# Implementation Plan: Material & Equipment System

## Phase 1: Database ## Phase 1: Database & Data Models Data Models [checkpoint: 54daa26]
This phase focus on extending the data storage and models to support equipment levels.

- [x] Task: Update `PlayersTable` 8821086 (80% Coverage)
    - [ ] Add `weapon_level`, `shield_level`, and `armor_level` columns (default 0).
    - [ ] Update `insertPlayer` and `toPlayer` mapping.
- [x] Task: Extend `Player` ee22fe6 and `RpgAttributes` Models (80% Coverage)
    - [ ] Add equipment level fields to the `Player` data class.
    - [ ] Update `RpgAttributes` to include equipment-based bonuses.
- [x] Task: Conductor - User Manual Verification 'Database - [ ] Task: Conductor - User Manual Verification 'Database & Data Models' Data Models' 54daa26 (Protocol in workflow.md)

## Phase 2: Upgrade Logic & Command
Implementing the core logic for upgrading equipment and the user command.

- [x] Task: Implement Equipment Upgrade Logic 4c1d78a (Per-resource quantities)
    - [ ] Write failing tests for material cost calculation (e.g., level * 10).
    - [ ] Implement logic to check material availability and deduct costs.
    - [ ] Implement stat bonus calculation (e.g., +5 per level).
- [x] Task: Create `/upgrade` ea3754b Slash Command (80% Coverage)
    - [ ] Register `/upgrade <type>` with autocompletion/choices (weapon, shield, armor).
    - [ ] Implement command handler to trigger upgrades and provide feedback.
- [x] Task: Conductor - User Manual Verification 'Upgrade Logic & Command' (Protocol in workflow.md)

## Phase 3: Integration & UI Updates
Finalizing the player experience and ensuring stats are correctly applied.

- [x] Task: Update `/status` Command (Completed in Phase 2)
- [x] Task: Integrate Equipment Bonuses into Exploration 87f6fdd (80% Coverage)
    - [ ] Update `/explore` to ensure combat calculations use the enhanced `RpgAttributes`.
    - [ ] Verify that equipment bonuses are correctly applied in monster encounters.
- [x] Task: Conductor - User Manual Verification 'Integration & UI Updates' (Protocol in workflow.md)
