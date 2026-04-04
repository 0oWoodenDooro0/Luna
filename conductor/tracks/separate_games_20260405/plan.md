# Implementation Plan: Separation of RPG and Undercover

## Phase 1: File Structure Reorganization
- [~] Task: Reorganize Main Codebase
    - [ ] Create base packages `luna.rpg`, `luna.undercover`, and `luna.core` in `src/main/kotlin/`.
    - [ ] Move Undercover-related files (`UndercoverManager.kt`, `UndercoverCommand.kt`, etc.) to the `luna.undercover` package.
    - [ ] Move RPG-related files (`rpg/`, etc.) to the `luna.rpg` package.
    - [ ] Move common/core files (`Command.kt`, `Main.kt`, shared database files) to `luna.core`.
    - [ ] Update `package` declarations across all moved `.kt` files.
    - [ ] Fix all `import` statements broken by the move.
- [ ] Task: Reorganize Test Codebase
    - [ ] Create corresponding base packages `luna.rpg`, `luna.undercover`, and `luna.core` in `src/test/kotlin/`.
    - [ ] Move existing test files to their respective packages to mirror the main source structure.
    - [ ] Update `package` declarations in test files.
    - [ ] Fix all `import` statements in test files.
- [ ] Task: Build Verification
    - [ ] Run `./gradlew clean build` to verify the project compiles correctly.
    - [ ] Run `./gradlew test` to ensure all tests execute and pass after the reorganization.
- [ ] Task: Conductor - User Manual Verification 'Phase 1: File Structure Reorganization' (Protocol in workflow.md)

## Phase 2: Documentation Update
- [ ] Task: Update Product Guidelines
    - [ ] Modify `conductor/product-guidelines.md` to document the new package architecture (`luna.rpg`, `luna.undercover`, `luna.core`).
    - [ ] Explicitly state the rules for where new features for each game should be placed.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Documentation Update' (Protocol in workflow.md)