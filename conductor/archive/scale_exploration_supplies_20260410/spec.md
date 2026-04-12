# Specification: Scale Exploration Supplies by Floor Level

## Overview
This track aims to make exploration rewards more dynamic and rewarding as players progress to deeper dungeon floors. Currently, resource room rewards are fixed within a random range (RESOURCE_MIN_AMOUNT to RESOURCE_MAX_AMOUNT). This feature will introduce a scaling mechanism that increases this range based on the player's current floor level.

## Functional Requirements
- **Scaling Mechanism:** Both `RESOURCE_MIN_AMOUNT` and `RESOURCE_MAX_AMOUNT` will increase linearly based on the current floor.
- **Formula:** 
  - `currentMin = baseMin + (floor - 1) * RESOURCE_SCALE_PER_FLOOR`
  - `currentMax = baseMax + (floor - 1) * RESOURCE_SCALE_PER_FLOOR`
  - `foundAmount = Random.nextInt(currentMin, currentMax + 1)`
- **Configuration:** Add a new configuration property `RESOURCE_SCALE_PER_FLOOR` to `config.yml` (default value: 10).
- **Player Bonus:** The final amount will still be multiplied by the player's resource bonus (from rebirth/prestige upgrades).

## Non-Functional Requirements
- **Consistency:** Ensure the scaling logic is consistent with how monster rewards are calculated.
- **Maintainability:** Refactor the resource calculation logic into a reusable method if appropriate.

## Acceptance Criteria
- [ ] `config.yml` includes the `resourceScalePerFloor` setting.
- [ ] `ExploreCommand` uses the new scaling formula for resource rooms.
- [ ] On Floor 1, the resource amount remains within the original range (Min/Max).
- [ ] On Floor 2+, the resource amount increases by `(floor - 1) * RESOURCE_SCALE_PER_FLOOR`.
- [ ] Automated tests verify the scaling logic for various floors.

## Out of Scope
- Scaling monster rewards (already implemented).
- Changing the probability of finding a resource room.
- Adding new types of resources.
