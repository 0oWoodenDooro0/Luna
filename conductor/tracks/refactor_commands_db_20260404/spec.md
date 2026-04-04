# Specification: Command Refactoring - Separating DB Logic

**Overview**
Refactor the RPG command layer to remove direct database dependencies (such as `transaction` blocks and `PlayersTable` access). All data persistence logic will be migrated into `PlayerRepository` to achieve a cleaner separation of concerns, improved maintainability, and enhanced testability.

**Functional Requirements**
1.  **Refactor `ExploreCommand.kt`**:
    -   Move `floorInfo` retrieval to `PlayerRepository`.
    -   Move resource gain logic (wood, stone, metal updates) to `PlayerRepository`.
    -   Move floor progression logic (`updateProgression`) to `PlayerRepository`.
2.  **Refactor `StatusCommand.kt`**:
    -   Move floor info and rooms explored retrieval to `PlayerRepository`.
3.  **Refactor `SettingsCommand.kt`**:
    -   Move `autoAdvance` setting update to `PlayerRepository`.
4.  **Consolidate Repository Methods**:
    -   Create reusable methods in `PlayerRepository` for common operations (e.g., `getPlayerProgression`, `updatePlayerProgression`, `addResources`).
5.  **Result Objects**:
    -   Use `Result` or custom sealed classes/data classes (e.g., `UpdateProgressionResult`) to return status and data from Repositories back to Commands.
6.  **Unit Testing**:
    -   Add comprehensive unit tests for all new `PlayerRepository` methods.

**Non-Functional Requirements**
-   Commands must only interact with `PlayerRepository` and `RpgModels`.
-   No `org.jetbrains.exposed` or `PlayersTable` imports in Command files.
-   Maintain existing game logic, behavior, and user-facing messages.

**Acceptance Criteria**
-   All `transaction` blocks are successfully removed from `ExploreCommand`, `StatusCommand`, and `SettingsCommand`.
-   All database updates and queries are moved to `PlayerRepository`.
-   Commands utilize high-level functions provided by the repository.
-   All unit tests pass, including new repository tests.

**Out of Scope**
-   Refactoring non-DB logic (e.g., combat simulation, random event rolling).
-   Changing the database schema.
-   Refactoring `UndercoverCommand` or `RevealCommand` (as they do not use the database).
