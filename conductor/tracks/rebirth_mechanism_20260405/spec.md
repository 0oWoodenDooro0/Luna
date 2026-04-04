# Specification: Rebirth Mechanism (Prestige)

## 1. Overview
Implement the final rebirth mechanism for the Luna Discord Text RPG. This feature allows players to prestige, resetting their progress in exchange for permanent stat-boosting points. The rebirth system rewards long-term progression and adds a strategic layer to endless dungeon exploration.

## 2. Functional Requirements
- **Rebirth Condition:** Players can only rebirth after reaching a configurable minimum level (floor).
- **Point Conversion (Milestones):** Players earn rebirth points based on clearing floor milestones above the minimum requirement.
- **Stat Upgrades:** Rebirth points can be spent to permanently upgrade specific player stats:
  - Attack Percentage (ATK%)
  - Defense Percentage (DEF%)
  - Speed Percentage (SPD%)
  - Recovery Percentage (康復%)
  - Health Points (HP%)
- **Upgrade Progression (Costs & Caps):** Each upgrade level costs progressively more points (diminishing returns). Each upgradable stat has a configurable maximum level (hard cap) to prevent breaking game balance.
- **Reset Scope (Hard Reset):** Upon rebirth, all current progress is reset to zero. This includes Level, Current HP, Equipment, and all Materials (Wood, Stone, Metal).
- **Configuration (Nested Config):** All parameters related to rebirth (minimum level, milestone intervals, points per milestone, stat upgrade costs, stat caps) will be centralized within a new `RebirthConfig` data class inside `RpgConfig`.

## 3. Non-Functional Requirements
- **Data Persistence:** Rebirth stats, rebirth count, and available rebirth points must be saved securely to the SQLite database using Exposed ORM.
- **Extensibility:** The new `RebirthConfig` must be easily adjustable without requiring code recompilation.

## 4. Acceptance Criteria
- [ ] The `RpgConfig` includes a nested `RebirthConfig` holding all relevant rebirth parameters.
- [ ] A new Discord command (e.g., `/rebirth`) allows players to perform a rebirth if they meet the minimum level requirement.
- [ ] Rebirthing resets the player's level, equipment, materials, and HP, while granting the correct amount of points based on floor milestones.
- [ ] A new Discord command (e.g., `/rebirth_upgrade`) allows players to spend points on ATK%, DEF%, SPD%, Recovery%, or HP%.
- [ ] Attempting to upgrade a stat beyond its maximum cap or without sufficient points fails gracefully with an appropriate user message.
- [ ] Rebirth upgrades progressively cost more points as their level increases.
- [ ] Upgraded stats correctly apply percentage bonuses to the player's base stats during combat and status display.
- [ ] Player state (rebirth points, upgraded stats, reset progress) is correctly persisted in the database.

## 5. Out of Scope
- Adding new material types specific to rebirth.
- Leaderboards or competitive rankings based on rebirth count.