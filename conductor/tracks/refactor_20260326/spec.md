# Specification: Architecture Unification and Generic Repository

## Overview
This track focuses on unifying the Luna bot's architecture, establishing a standard generic repository pattern, and dramatically increasing test coverage. The primary goal is to remove the need to write new repository functions for every new command by making data access generic and interface-driven, conforming to a structured Layered Architecture.

## Goals & Scope
- **Architecture:** Implement a strict Layered Architecture separating Command, Service, and Repository logic.
- **Data Access:** Refactor existing repositories to implement a generic interface (e.g., `IRepository<T, ID>`) to handle standard CRUD operations, reducing boilerplate for new entities.
- **Testing:** Add comprehensive automated tests across all major layers:
  - Repositories/Database interactions (Exposed ORM).
  - Command logic and execution.
  - Business logic within Services (e.g., LevelingService).

## Out of Scope
- Adding new user-facing commands or features.
- Changing the underlying database technology (SQLite/Exposed remains).
- Modifying the Discord library (Kord remains).

## Acceptance Criteria
- [ ] All database entities use a generic interface for basic data access.
- [ ] Commands no longer directly interact with raw database operations; they use Services or Repositories.
- [ ] The project achieves >80% test coverage across Repository, Service, and Command layers.
- [ ] Existing functionality (e.g., Leveling, Hunts) remains unbroken and passes all new tests.