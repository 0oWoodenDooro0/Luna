# Implementation Plan: Implement Core RPG Stats and Exploration Mechanics

## Phase 1: Data Models and Persistence [checkpoint: 5c6129e]
- [x] Task: Define RPG Core Data Models (62d3f62)
    - [x] Create `RpgAttributes` data class (HP, ATK, DEF, SPD).
    - [x] Create `Player` and `Monster` classes using `RpgAttributes`.
- [x] Task: Set Up SQLite Tables for RPG (87dc2af)
    - [x] Define `PlayersTable` in Exposed (id, hp, max_hp, atk, def, spd, wood, stone, metal, current_floor).
    - [x] Initialize tables in a new `DatabaseManager`.
- [x] Task: Conductor - User Manual Verification 'Data Models and Persistence' (Protocol in workflow.md) (5c6129e)

## Phase 2: Core Commands and Exploration [checkpoint: 8702d8a]
- [x] Task: Implement /status Command (58beda5)
    - [x] Register `/status` slash command.
    - [x] Implement handler to fetch and display player stats via Rich Embed.
- [x] Task: Implement Basic /explore Command (ba1c4b7)
    - [x] Register `/explore` slash command.
    - [x] Implement random event logic: 50% chance of finding resources, 50% chance of encountering a monster.
    - [x] Implement simple automated combat logic.
- [x] Task: Implement Floor Advancement Control (fb8ea56)
    - [x] Add `auto_advance` column to `PlayersTable`.
    - [x] Implement `/auto_advance` toggle command.
    - [x] Implement `/next_floor` manual command.
    - [x] Update `/explore` to honor the `auto_advance` setting.
- [x] Task: Implement Room-based Progression (496dc46)
    - [x] Add `rooms_explored` and `floor_size` columns to `PlayersTable`.
    - [x] Replace `/auto_advance` and `/next_floor` with a unified `/settings` command.
    - [x] Update `/explore` to increment rooms and handle transition based on `floor_size`.
    - [x] Update `/status` to display room progress.
- [x] Task: System-wide Configuration (1111669)
    - [x] Create `RpgConfig` for global constants (FLOOR_SIZE = 5).
    - [x] Change `auto_advance` default to `true`.
    - [x] Remove `floor_size` from database.
- [x] Task: Conductor - User Manual Verification 'Core Commands and Exploration' (Protocol in workflow.md) (8702d8a)
