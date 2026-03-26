# Implementation Plan: Architecture Unification and Generic Repository

## Phase 1: Generic Repository Setup [checkpoint: 1ef4f74]
- [x] Task: Define the Generic Repository Interface 62bf9ba
    - [x] Write failing tests for generic repository data access logic (using a test entity).
    - [x] Implement `IRepository<T, ID>` interface and base Exposed-backed implementation.
- [x] Task: Conductor - User Manual Verification 'Phase 1: Generic Repository Setup' (Protocol in workflow.md) 1ef4f74

## Phase 2: Refactor Repositories [checkpoint: bcc8baa]
- [x] Task: Refactor PlayerRepository 16e668a
    - [x] Update and expand `PlayerRepositoryTest` to test generic CRUD functionality.
    - [x] Refactor `PlayerRepository` to implement the new generic interface.
- [x] Task: Conductor - User Manual Verification 'Phase 2: Refactor Repositories' (Protocol in workflow.md) bcc8baa

## Phase 3: Refactor Services
- [x] Task: Refactor LevelingService bcc8baa
    - [x] Write failing tests for `LevelingService` business logic using mocked repositories.
    - [x] Refactor `LevelingService` to depend on the newly structured generic data layer.
- [x] Task: Conductor - User Manual Verification 'Phase 3: Refactor Services' (Protocol in workflow.md) bcc8baa

## Phase 4: Refactor Commands
- [x] Task: Refactor InitCommand bcc8baa
    - [x] Write failing tests for `InitCommand` using MockK to mock Kord and services.
    - [x] Refactor `InitCommand` to adhere to the Layered Architecture.
- [x] Task: Refactor HuntCommand bcc8baa
    - [x] Write failing tests for `HuntCommand` using mocked dependencies.
    - [x] Refactor `HuntCommand` to adhere to the Layered Architecture.
- [x] Task: Conductor - User Manual Verification 'Phase 4: Refactor Commands' (Protocol in workflow.md) bcc8baa