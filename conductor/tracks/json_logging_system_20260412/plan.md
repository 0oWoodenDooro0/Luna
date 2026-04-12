# Implementation Plan: Comprehensive JSON Logging System

## Phase 1: Infrastructure & Foundation
**Goal:** Set up JSON logging dependencies, configuration, and size-based rotation.

- [x] Task: Update `build.gradle.kts` with Logback and JSON logging dependencies 59843ca
- [x] Task: Create `src/main/resources/logback.xml` with RollingFileAppender (10MB total limit) 47ded3c
- [x] Task: Create `JsonLogger` utility class for structured JSON logging across layers 97dab83
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Infrastructure & Foundation' (Protocol in workflow.md)

## Phase 2: Database Layer Logging
**Goal:** Integrate JSON logging into Exposed/SQLite queries.

- [ ] Task: Create `SqlLogger` implementation for Exposed `SqlLogger` interface
- [ ] Task: Register `SqlLogger` in `DatabaseManager` to capture all queries and results
- [ ] Task: Verify DB logs follow the JSON schema and are persisted to the log file
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Database Layer Logging' (Protocol in workflow.md)

## Phase 3: Service Layer Logging
**Goal:** Add manual JSON logging to critical service methods.

- [ ] Task: Integrate `JsonLogger` into `MapService` for floor changes and exploration
- [ ] Task: Integrate `JsonLogger` into `CombatEngine` for turn-based combat events
- [ ] Task: Integrate `JsonLogger` into `PlayerRepository` for data persistence events
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Service Layer Logging' (Protocol in workflow.md)

## Phase 4: Command Layer Logging (Global Middleware)
**Goal:** Automatically log all command inputs, parameters, and outputs.

- [ ] Task: Modify `Command` base interface or `Main.kt` dispatcher to wrap command execution
- [ ] Task: Capture command parameters and user context for the JSON log
- [ ] Task: Capture command responses (messages sent to Discord) as command output
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Command Layer Logging' (Protocol in workflow.md)

## Phase 5: Final Validation & Cleanup
**Goal:** Ensure 10MB limit compliance and overall system stability.

- [ ] Task: Stress test logging with high frequency to verify file rotation and 10MB limit
- [ ] Task: Final audit of log format consistency across all layers
- [ ] Task: Remove any legacy `println` or basic SLF4J logs if redundant
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Final Validation & Cleanup' (Protocol in workflow.md)
