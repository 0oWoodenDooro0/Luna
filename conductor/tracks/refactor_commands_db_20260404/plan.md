# Implementation Plan: Command Refactoring - Separating DB Logic

**Phase 1: Repository Expansion & Unit Testing**
- [ ] Task: Create `Result` objects or extension properties on `Player` to hold progression data.
- [ ] Task: Implement `PlayerRepository.getProgression(userId: String): Pair<Int, Int>` to retrieve floor and rooms explored.
- [ ] Task: Implement `PlayerRepository.addResources(userId: String, resource: String, amount: Int)` to handle material gains.
- [ ] Task: Implement `PlayerRepository.updateProgression(userId: String, floor: Int, rooms: Int, autoAdvance: Boolean): Pair<Int, String>` to centralize floor/room logic.
- [ ] Task: Implement `PlayerRepository.updateAutoAdvance(userId: String, autoAdvance: Boolean)` for `SettingsCommand`.
- [ ] Task: Write unit tests for these new methods in `PlayerRepositoryTest.kt` (or a new test file if appropriate).
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Repository Expansion' (Protocol in workflow.md)

**Phase 2: Refactoring Commands**
- [ ] Task: Refactor `StatusCommand.kt` to use `PlayerRepository.getProgression`.
- [ ] Task: Refactor `SettingsCommand.kt` to use `PlayerRepository.updateAutoAdvance`.
- [ ] Task: Refactor `ExploreCommand.kt`:
    - [ ] Replace direct `transaction` blocks with calls to `PlayerRepository.getProgression`, `PlayerRepository.addResources`, and `PlayerRepository.updateProgression`.
    - [ ] Ensure all logic previously in `updateProgression` (private function in command) is moved to the repository.
- [ ] Task: Remove all Exposed-related imports (`org.jetbrains.exposed.*`, `PlayersTable`, etc.) from the command files.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Refactoring Commands' (Protocol in workflow.md)

**Phase 3: Verification & Cleanup**
- [ ] Task: Run the full test suite (`./gradlew test`) to ensure zero regressions in game logic.
- [ ] Task: Verify that no `transaction` blocks or table references remain in the target commands.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Final Verification' (Protocol in workflow.md)
