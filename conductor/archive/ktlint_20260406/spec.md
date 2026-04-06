# Specification: ktlint Integration

## Objective
To enforce a consistent code style and formatting across the entire Kotlin codebase using `ktlint` via the `org.jmailen.kotlinter` Gradle plugin (version 5.4.2).

## Motivation
Maintaining a consistent code style is crucial for readability, maintainability, and collaboration. `ktlint` is a "no-configuration" Kotlin linter and formatter that adheres to the official Kotlin Style Guide and Android Kotlin Style Guide.

## Scope
- Integration of `org.jmailen.kotlinter` version 5.4.2 into `build.gradle.kts`.
- Formatting of all existing Kotlin source files (`src/main/kotlin` and `src/test/kotlin`).
- Updating the project workflow to include linting as a quality gate.
- Updating `conductor/workflow.md` to include `ktlintCheck` in the "Before Committing" and "Quality Gates" sections.

## Constraints & Requirements
- Must use `org.jmailen.kotlinter` version 5.4.2.
- The project should build and pass all existing tests after formatting.
- Linting must be part of the `check` task.
- Workflow documentation must be updated to reflect the new linting requirements.

## Proposed Changes
1.  **Build Configuration:**
    - Add `id("org.jmailen.kotlinter") version "5.4.2"` to the `plugins` block in `build.gradle.kts`.
2.  **Workflow Update:**
    - Update `conductor/workflow.md` to include `./gradlew ktlintCheck` in the "Before Committing" section and in the "Quality Gates" list.
3.  **Code Formatting:**
    - Run `./gradlew ktlintFormat` to automatically fix formatting issues.
    - Manually fix any remaining issues reported by `./gradlew ktlintCheck`.

## Verification Plan
- Run `./gradlew ktlintCheck` and ensure it passes for all source files.
- Run `./gradlew build` (which includes `check`) to ensure the entire project is still valid.
- Verify that the workflow documentation is accurate.
