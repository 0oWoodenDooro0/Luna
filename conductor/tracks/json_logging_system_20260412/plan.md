# Implementation Plan: Comprehensive JSON Logging System

## Phase 1: Infrastructure & Foundation [checkpoint: 255c23c]
**Goal:** Set up JSON logging dependencies, configuration, and size-based rotation.

- [x] Task: Update `build.gradle.kts` with Logback and JSON logging dependencies 59843ca
- [x] Task: Create `src/main/resources/logback.xml` with RollingFileAppender (10MB total limit) 47ded3c
- [x] Task: Create `JsonLogger` utility class for structured JSON logging across layers 97dab83
- [x] Task: Conductor - User Manual Verification 'Phase 1: Infrastructure & Foundation' (Protocol in workflow.md) 255c23c

## Phase 2: Database Layer Logging [checkpoint: b90cede]
**Goal:** Integrate JSON logging into Exposed/SQLite queries.

- [x] Task: Create `SqlLogger` implementation for Exposed `SqlLogger` interface b90cede
- [x] Task: Register `SqlLogger` in `DatabaseManager` to capture all queries and results b90cede
- [x] Task: Verify DB logs follow the JSON schema and are persisted to the log file b90cede
- [x] Task: Conductor - User Manual Verification 'Phase 2: Database Layer Logging' (Protocol in workflow.md) b90cede

## Phase 3: Service Layer Logging [checkpoint: 7adaed7]
**Goal:** Add manual JSON logging to critical service methods.

- [x] Task: Integrate `JsonLogger` into `MapService` for floor changes and exploration 7adaed7
- [x] Task: Integrate `JsonLogger` into `CombatEngine` for turn-based combat events 7adaed7
- [x] Task: Integrate `JsonLogger` into `PlayerRepository` for data persistence events 7adaed7
- [x] Task: Conductor - User Manual Verification 'Phase 3: Service Layer Logging' (Protocol in workflow.md) 7adaed7

## Phase 4: Command Layer Logging (Global Middleware) [checkpoint: 1b5ec02]
**Goal:** Automatically log all command inputs, parameters, and outputs.

- [x] Task: Modify `Command` base interface or `Main.kt` dispatcher to wrap command execution 1b5ec02
- [x] Task: Capture command parameters and user context for the JSON log 1b5ec02
- [x] Task: Capture command responses (messages sent to Discord) as command output 1b5ec02
- [x] Task: Conductor - User Manual Verification 'Phase 4: Command Layer Logging' (Protocol in workflow.md) 1b5ec02

## Phase 5: Final Validation & Cleanup
**Goal:** Ensure 10MB limit compliance and overall system stability.

- [x] Task: Stress test logging with high frequency to verify file rotation and 10MB limit aa875a9
- [x] Task: Final audit of log format consistency across all layers aa875a9
- [x] Task: Remove any legacy `println` or basic SLF4J logs if redundant aa875a9
- [~] Task: Conductor - User Manual Verification 'Phase 5: Final Validation & Cleanup' (Protocol in workflow.md)
