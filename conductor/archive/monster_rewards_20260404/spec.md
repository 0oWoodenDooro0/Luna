# Specification: Monster Rewards & Configuration Centralization

## Overview
This track focuses on two main objectives:
1. Adding a material reward system for defeating monsters in the RPG dungeon.
2. Centralizing all hardcoded game parameters (monster scaling, exploration logic, upgrade costs) into `RpgConfig.kt` with clear categorization for easier adjustments.

## Functional Requirements
### 1. Monster Defeat Rewards
- Every monster defeated in combat must drop a material reward (🪵 木頭, 🪨 石頭, or 🔗 金屬).
- The drop is guaranteed (100% chance).
- The reward amount will scale with the current floor level.
- The formula for the reward will be configurable in `RpgConfig.kt`.
- The reward will be added to the player's inventory immediately after a successful combat.

### 2. Configuration Centralization in `RpgConfig.kt`
- Move all hardcoded parameters from `ExploreCommand.kt`, `CombatEngine.kt`, and other relevant files to `RpgConfig.kt`.
- Organize constants into clear categories using objects or groups within `RpgConfig.kt`.
- Categories to include:
    - **Exploration Settings:** Event rates, floor size, room progression.
    - **Monster Settings:** Base attributes, scaling factors per floor.
    - **Economy & Reward Settings:** Material drop ranges, scaling formulas, upgrade costs.
    - **Recovery Settings:** Cooldown factors, upgrade reductions.

## Non-Functional Requirements
- Maintain backward compatibility with existing player data.
- Ensure all moved parameters are easily adjustable without changing logic code.
- High code coverage for new and refactored logic.

## Acceptance Criteria
- Defeating a monster correctly adds resources to the player's profile.
- The combat victory message displays the reward received.
- `ExploreCommand.kt` and other logic files no longer contain hardcoded numeric game constants.
- `RpgConfig.kt` is organized into logical data-type groupings.
- All unit tests pass, and new tests cover the reward logic.

## Out of Scope
- Adding new material types.
- Implementing an experience or gold system.
- Adding complex drop tables with different probabilities for different monsters.
