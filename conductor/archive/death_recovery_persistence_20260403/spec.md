# Specification: Death Recovery & Monster Persistence

## Overview
This feature introduces a persistent death recovery mechanism in the exploration mode. Instead of ending the exploration or resetting the room upon death, players will revive in the same room and must defeat the current monster (which retains its full state) to progress.

## Functional Requirements
- **Persistent Monster State:** When a player dies during a monster encounter in `explore`, the **full state** of the monster (including health, attack, defense, and any other attributes) must be saved to the database.
- **Revival in Room:** Players who die in `explore` do not exit the exploration or complete the room. They remain in the current room in a "reviving" state.
- **Fight Resumption:** The fight does **not** resume automatically. It is triggered when the player uses the `explore` command again.
- **Pre-requisites for Resumption:** Before resuming the fight, the system must check if the player is alive (has reached full health) and if an unfinished fight exists.
- **Mandatory Progress:** The player cannot enter the next room or floor until the current monster is defeated.
- **UI/Feedback:** Display appropriate messages during death and when the player attempts to `explore` while still reviving or when resuming a fight.

## Acceptance Criteria
- [ ] If a player dies, the exploration does not end.
- [ ] The monster's **full attributes (HP, ATK, DEF, etc.)** are saved at the moment of the player's death.
- [ ] Using `explore` while dead or recovering shows a "Still reviving" message.
- [ ] Using `explore` while alive and having an unfinished fight resumes the battle with the saved monster state.
- [ ] The monster starts with the **exact saved state** from the previous encounter.
- [ ] Progress to the next room is only possible after the monster's death.

## Out of Scope
- Redesigning the entire combat system.
- Changes to other commands (Reveal, Undercover).
