# Track Specification: Player Leveling and Experience System

## Overview
Implement a comprehensive leveling and experience point (XP) system for the Luna Discord bot. This system will allow players to gain XP through activities (starting with hunts) and level up as they reach specific XP thresholds, providing a sense of progression and growth.

## Requirements
- **Player XP & Level Storage:** Extend the existing `Player` model and database schema to include `xp` and `level` fields.
- **XP Gain Mechanism:** Implement logic to award XP to players upon the successful completion of a hunt.
- **Level-Up Logic:**
  - Define XP thresholds for each level (e.g., using a formula or a static table).
  - Automatically increment a player's level when their XP exceeds the next threshold.
- **Persistence:** Ensure all XP and level changes are correctly persisted in the SQLite database using the Exposed ORM.
- **User Feedback:** Update Discord messages to inform players when they gain XP or reach a new level.

## Technical Details
- **Data Model:** Update the `PlayerRepository` and associated database tables.
- **Formulas:** Use a simple quadratic formula for XP thresholds (e.g., `xp_to_next_level = level * 100`).
- **Discord Interactions:** Utilize Kord's interaction system to provide real-time feedback in response to commands.

## Acceptance Criteria
- [ ] A player's current XP and level can be retrieved from the database.
- [ ] XP is correctly awarded and stored after a successful hunt.
- [ ] A player's level increments automatically when the XP threshold is met.
- [ ] Discord messages clearly display XP gains and level-up announcements.
- [ ] All new functionality is covered by unit tests with >80% coverage.
