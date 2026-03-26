# Implementation Plan: Architecture Unification and Generic Repository

## Phase 1: Generic Repository Setup
- [x] Task: Define the Generic Repository Interface 62bf9ba
    - [x] Write failing tests for generic repository data access logic (using a test entity).
    - [x] Implement `IRepository<T, ID>` interface and base Exposed-backed implementation.
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Generic Repository Setup' (Protocol in workflow.md)

## Phase 2: Refactor Repositories
- [ ] Task: Refactor PlayerRepository
    - [ ] Update and expand `PlayerRepositoryTest` to test generic CRUD functionality.
    - [ ] Refactor `PlayerRepository` to implement the new generic interface.
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Refactor Repositories' (Protocol in workflow.md)

## Phase 3: Refactor Services
- [ ] Task: Refactor LevelingService
    - [ ] Write failing tests for `LevelingService` business logic using mocked repositories.
    - [ ] Refactor `LevelingService` to depend on the newly structured generic data layer.
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Refactor Services' (Protocol in workflow.md)

## Phase 4: Refactor Commands
- [ ] Task: Refactor InitCommand
    - [ ] Write failing tests for `InitCommand` using MockK to mock Kord and services.
    - [ ] Refactor `InitCommand` to adhere to the Layered Architecture.
- [ ] Task: Refactor HuntCommand
    - [ ] Write failing tests for `HuntCommand` using mocked dependencies.
    - [ ] Refactor `HuntCommand` to adhere to the Layered Architecture.
- [ ] Task: Conductor - User Manual Verification 'Phase 4: Refactor Commands' (Protocol in workflow.md)