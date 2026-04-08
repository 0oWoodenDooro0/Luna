# Track Specification: rebirth_upgrades_expansion_20260407

## Overview
Add two new rebirth upgrades to the RPG system: "Resourceful" (increases material gain) and "Efficient" (call cost reduction). These upgrades will provide permanent progression benefits across rebirths.

## Functional Requirements
1.  **New Rebirth Upgrades:**
    -   **Resourceful (物資豐富):** Increases the amount of resources (Wood, Stone, Metal) gained from exploration and monster drops.
    -   **Efficient (升級效率):** Reduces the material cost required for upgrading equipment (Weapon, Shield, Armor, Recovery).
2.  **Stat Scaling:**
    -   **Base Effect:** 5% bonus per level (Increase gain / Decrease cost).
    -   **Initial Cost:** 1 Rebirth Point.
    -   **Cost Scaling:** Follows the existing rebirth upgrade cost formula: `BASE_UPGRADE_COST + (currentLevel * COST_INCREASE_PER_LEVEL)`.
    -   **Max Level:** 10 levels for each.
3.  **Configuration:**
    -   New settings in `config.yaml` to control the bonus percentage and max levels for these upgrades.
4.  **Database Persistence:**
    -   Update `PlayersTable` to store the levels of these two new upgrades.
5.  **User Interface:**
    -   Update `/rebirth list` to display the new upgrades.
    -   Update `/rebirth upgrade` to handle the new upgrade types.
    -   Apply "Resourceful" bonus in resource gathering logic.
    -   Apply "Efficient" bonus in equipment upgrade logic.

## Non-Functional Requirements
- Maintain consistency with existing rebirth upgrade logic.
- Ensure type safety and follow Kotlin style guides.

## Acceptance Criteria
- [ ] Players can see "Resourceful" and "Efficient" in the rebirth upgrade list.
- [ ] Players can spend rebirth points to level up these upgrades.
- [ ] Material gain is increased by 5% per level of "Resourceful".
- [ ] Upgrade costs are decreased by 5% per level of "Efficient".
- [ ] Upgrades are capped at level 10.
- [ ] Bonuses are correctly saved and loaded from the database.
- [ ] Configuration values can be adjusted in `config.yml`.

## Out of Scope
- Adding new equipment types.
- Changes to the core rebirth point calculation.
