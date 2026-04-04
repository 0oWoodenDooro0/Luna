# Implementation Plan: Command Refactoring - Separating DB Logic

**Phase 1: Repository Expansion & Unit Testing [checkpoint: e6e80ce]**
- [x] Task: Create `Result` objects or extension properties on `Player` to hold progression data. a4583bf
- [x] Task: Implement `PlayerRepository.getProgression(userId: String): Pair<Int, Int>` to retrieve floor and rooms explored. a4583bf
- [x] Task: Implement `PlayerRepository.addResources(userId: String, resource: String, amount: Int)` to handle material gains. a4583bf
- [x] Task: Implement `PlayerRepository.updateProgression(userId: String, floor: Int, rooms: Int, autoAdvance: Boolean): Pair<Int, String>` to centralize floor/room logic. a4583bf
- [x] Task: Implement `PlayerRepository.updateAutoAdvance(userId: String, autoAdvance: Boolean)` for `SettingsCommand`. a4583bf
- [x] Task: Write unit tests for these new methods in `PlayerRepositoryTest.kt` (or a new test file if appropriate). a4583bf
- [x] Task: Conductor - User Manual Verification 'Phase 1: Repository Expansion' (Protocol in workflow.md) e6e80ce

**Phase 2: Refactoring Commands [checkpoint: c09c946]**
- [x] Task: Refactor `StatusCommand.kt` to use `PlayerRepository.getProgression`. bc00d2c
- [x] Task: Refactor `SettingsCommand.kt` to use `PlayerRepository.updateAutoAdvance`. bc00d2c
- [x] Task: Refactor `ExploreCommand.kt`:
    - [x] Replace direct `transaction` blocks with calls to `PlayerRepository.getProgression`, `PlayerRepository.addResources`, and `PlayerRepository.updateProgression`. bc00d2c
    - [x] Ensure all logic previously in `updateProgression` (private function in command) is moved to the repository. bc00d2c
- [x] Task: Remove all Exposed-related imports (`org.jetbrains.exposed.*`, `PlayersTable`, etc.) from the command files. bc00d2c
- [x] Task: Conductor - User Manual Verification 'Phase 2: Refactoring Commands' (Protocol in workflow.md) c09c946

**Phase 3: Verification & Cleanup [checkpoint: cbbfe8d]**
- [x] Task: Run the full test suite (`./gradlew test`) to ensure zero regressions in game logic. e9a8b4c
- [x] Task: Verify that no `transaction` blocks or table references remain in the target commands. 554c87f
- [x] Task: Conductor - User Manual Verification 'Phase 3: Final Verification' (Protocol in workflow.md) cbbfe8d
