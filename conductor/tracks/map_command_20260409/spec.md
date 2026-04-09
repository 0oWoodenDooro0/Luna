# Specification: /map Command & Dungeon Map System

## Overview
The `/map` command allows players to create custom, reusable dungeon maps for exploration. Players can specify the map's resource drop rate and dungeon layer. The map size is fixed at 20 rooms. Creating a map consumes existing materials (Wood, Stone, Metal) proportional to the map's parameters. Once created, a map is stored in the player's map inventory and can be explored using the `/explore` command, which will automatically progress through the map's rooms.

## Functional Requirements
- **Command: `/map create`**
  - Parameters:
    - `drop_rate`: The resource multiplier (60% to 150%).
    - `layer`: The dungeon floor level or biome to base the map on.
  - **Fixed Size:** The map size is always 20 rooms.
  - Cost Calculation:
    - The cost (Wood, Stone, Metal) increases with `drop_rate`.
    - Higher `layer` levels may increase the base cost or require rarer materials (if implemented later).
  - Validation:
    - Ensure the player has enough resources.
    - Validate `drop_rate` is within the 60-150% range.
  - Success: Deduct resources and add the new map to the player's inventory.

- **Command: `/map list`**
  - Display all currently owned maps with their parameters (`drop_rate`, `layer`, `id`).

- **Command: `/map select <map_id>`**
  - Set a specific map as the active map for exploration.

- **Command: `/map delete <map_id>`**
  - Remove a map from the inventory.

- **Integration with `/explore`**
  - If an active map is selected:
    - `/explore` will automatically move the player to the next room in the map (1 to 20).
    - The drop rate for resources and monsters will be multiplied by the map's `drop_rate`.
    - Once the 20th room is reached, the player can restart the map or select a new one.
  - Cancelled: The current "auto-advance" setting for normal exploration is replaced by this map-based progression when a map is active.

- **Data Persistence**
  - Store player maps in the SQLite database (new table: `player_maps`).
  - Track the player's current room/progress within the active map.

## Non-Functional Requirements
- **Scalability:** Ensure the map system can handle a large number of player maps.
- **Performance:** Database queries for map creation and retrieval should be optimized.
- **User Feedback:** Provide clear error messages if resources are insufficient or parameters are invalid.

## Acceptance Criteria
- [ ] Players can successfully create a map with valid parameters.
- [ ] Resource costs are correctly calculated and deducted.
- [ ] Players can view their map inventory.
- [ ] The `/explore` command correctly uses the active map's parameters.
- [ ] Exploration progress within a map is correctly saved and resumed.
- [ ] Players can switch between different maps.

## Out of Scope
- Custom map sizes (fixed at 20).
- Trading maps between players.
- Complex map layouts (e.g., branching paths).
- Special map-only monsters (for now).
