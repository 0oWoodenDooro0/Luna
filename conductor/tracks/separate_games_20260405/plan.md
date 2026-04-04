# Implementation Plan: Separation of RPG and Undercover

## Phase 1: File Structure Reorganization [checkpoint: c6bd416]
- [x] Task: Reorganize Main Codebase 7085a83
    - [x] Create base packages `luna.rpg`, `luna.undercover`, and `luna.core` in `src/main/kotlin/`.
    - [x] Move Undercover-related files (`UndercoverManager.kt`, `UndercoverCommand.kt`, etc.) to the `luna.undercover` package.
    - [x] Move RPG-related files (`rpg/`, etc.) to the `luna.rpg` package.
    - [x] Move common/core files (`Command.kt`, `Main.kt`, shared database files) to `luna.core`.
    - [x] Update `package` declarations across all moved `.kt` files.
    - [x] Fix all `import` statements broken by the move.
- [x] Task: Reorganize Test Codebase 7085a83
    - [x] Create corresponding base packages `luna.rpg`, `luna.undercover`, and `luna.core` in `src/test/kotlin/`.
    - [x] Move existing test files to their respective packages to mirror the main source structure.
    - [x] Update `package` declarations in test files.
    - [x] Fix all `import` statements in test files.
- [x] Task: Build Verification 7085a83
    - [x] Run `./gradlew clean build` to verify the project compiles correctly.
    - [x] Run `./gradlew test` to ensure all tests execute and pass after the reorganization.
- [x] Task: Conductor - User Manual Verification 'Phase 1: File Structure Reorganization' (Protocol in workflow.md) c6bd416

## Phase 2: Documentation Update
- [x] Task: Update Product Guidelines d38d3cf
    - [x] Modify `conductor/product-guidelines.md` to document the new package architecture (`luna.rpg`, `luna.undercover`, `luna.core`).
    - [x] Explicitly state the rules for where new features for each game should be placed.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Documentation Update' (Protocol in workflow.md)