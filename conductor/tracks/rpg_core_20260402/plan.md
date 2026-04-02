# Implementation Plan: Implement Core RPG Stats and Exploration Mechanics

## Phase 1: Data Models and Persistence [checkpoint: 5c6129e]
- [x] Task: Define RPG Core Data Models (62d3f62)
    - [x] Create `RpgAttributes` data class (HP, ATK, DEF, SPD).
    - [x] Create `Player` and `Monster` classes using `RpgAttributes`.
- [x] Task: Set Up SQLite Tables for RPG (87dc2af)
    - [x] Define `PlayersTable` in Exposed (id, hp, max_hp, atk, def, spd, wood, stone, metal, current_floor).
    - [x] Initialize tables in a new `DatabaseManager`.
- [x] Task: Conductor - User Manual Verification 'Data Models and Persistence' (Protocol in workflow.md) (5c6129e)

## Phase 2: Core Commands and Exploration
- [ ] Task: Implement /status Command
    - [ ] Register `/status` slash command.
    - [ ] Implement handler to fetch and display player stats via Rich Embed.
- [ ] Task: Implement Basic /explore Command
    - [ ] Register `/explore` slash command.
    - [ ] Implement random event logic: 50% chance of finding resources, 50% chance of encountering a monster.
    - [ ] Implement simple automated combat logic.
- [ ] Task: Conductor - User Manual Verification 'Core Commands and Exploration' (Protocol in workflow.md)
