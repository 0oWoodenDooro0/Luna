# Overview
This track focuses on structurally separating the codebases for the RPG game (Luna) and the Undercover game. The goal is to establish a clear architectural boundary between the two features, organizing their respective files into distinct top-level packages. Additionally, the reorganized project file structure will be documented in `conductor/product-guidelines.md` to ensure future development follows this separation.

# Functional Requirements
1. **Package Reorganization:** Move existing files into dedicated top-level packages (e.g., `luna.rpg` and `luna.undercover`), and establish a common/core package if needed.
2. **Namespace Updates:** Update package declarations and import statements across all Kotlin source files (both `main` and `test`) to reflect the new structure.
3. **Test Code Reorganization:** Reorganize the `src/test/kotlin/` directory to strictly mirror the new main source package structure.
4. **Documentation Update:** Update `conductor/product-guidelines.md` to explicitly describe the new project file structure and the separation between the RPG and Undercover games.

# Non-Functional Requirements
- **Functionality Preservation:** The structural changes must not alter the existing behavior of either the RPG or the Undercover game. All unit tests must pass after the reorganization.
- **Maintainability:** The new structure should make it obvious where new commands, repositories, or models for either game should be placed.

# Acceptance Criteria
- [ ] Source files for RPG and Undercover are entirely contained within their respective separated packages.
- [ ] The project compiles successfully (`./gradlew build`).
- [ ] All automated tests pass successfully (`./gradlew test`).
- [ ] `conductor/product-guidelines.md` contains a clear, up-to-date representation of the file structure.

# Out of Scope
- Adding new features to either the RPG or Undercover game.
- Rewriting the internal logic of the games beyond what is required for package and import changes.