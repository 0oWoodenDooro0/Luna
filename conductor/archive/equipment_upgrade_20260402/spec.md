# Specification: Material & Equipment System

## Overview
Implement an equipment upgrade system where players can use collected materials (Wood, Stone, Metal) to enhance their gear. This will provide a long-term progression path beyond base attributes.

## Functional Requirements
1.  **Equipment Categories:**
    *   **Weapon:** Increases Attack Power (ATK).
    *   **Shield:** Increases Defense (DEF).
    *   **Armor:** Increases Maximum Health (HP).
2.  **Upgrade Mechanism:**
    *   Players can upgrade each piece of equipment using materials collected during exploration.
    *   **Cost Scaling:** Material cost increases with equipment level (e.g., Level 1 -> 2 costs 10, Level 2 -> 3 costs 20, etc.).
    *   **Stat Increase:** Each level provides a flat increase to the relevant attribute.
    *   **Max Level:** No hard limit; players can upgrade as long as they have materials.
3.  **User Interface:**
    *   **`/upgrade <type>`:** Command to upgrade a specific equipment (weapon, shield, or armor).
    *   **`/status`:** Updated to display current equipment levels and the total attribute bonuses.
4.  **Integration:**
    *   Combat and exploration logic must account for the additional attributes provided by equipment.

## Non-Functional Requirements
*   **Data Persistence:** Equipment levels must be stored in the `players` table.
*   **Input Validation:** Ensure players have sufficient materials before allowing an upgrade.

## Acceptance Criteria
*   Players can successfully upgrade their weapon, shield, and armor using the `/upgrade` command.
*   The `/status` command correctly reflects equipment levels and bonuses.
*   Material costs scale correctly according to the level.
*   Equipment bonuses are correctly applied in combat and when checking status.

## Out of Scope
*   Unique equipment items or RNG-based loot.
*   Equipment durability or repair mechanics.
*   Trading equipment between players.
