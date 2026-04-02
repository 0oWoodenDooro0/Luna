# Technology Stack: Luna Discord RPG

## Core Language & Runtime
- **Kotlin (Java 26):** Modern, expressive language for the JVM, providing strong type safety and coroutines for asynchronous Discord interactions.
- **Gradle (Kotlin DSL):** Build tool for dependency management and project automation.

## Discord Integration
- **Kord:** A coroutine-based Discord library for Kotlin. It provides an idiomatic and high-performance way to interact with the Discord API.

## Data Persistence
- **Exposed (JetBrains):** An ORM framework for Kotlin that provides a type-safe API for working with SQL databases.
- **SQLite (JDBC):** A lightweight, file-based SQL database for local persistence of player data, equipment, and resources.

## Development & Testing
- **SLF4J (Simple):** Simple logging facade for basic output and debugging.
- **Kotlin Test:** Standard library for unit and integration testing.
- **MockK:** A powerful mocking library for Kotlin, used to simulate Discord events and database interactions in tests.
