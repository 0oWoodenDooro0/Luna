# Technology Stack: Luna Discord RPG

## Core Language & Runtime
- **Kotlin (JVM 17):** Modern, expressive language for the JVM, providing strong type safety and coroutines for asynchronous Discord interactions.
- **Gradle (Kotlin DSL):** Build tool for dependency management and project automation.

## Discord Integration
- **Kord:** A coroutine-based Discord library for Kotlin. It provides an idiomatic and high-performance way to interact with the Discord API.

## Data Persistence
- **Exposed (JetBrains):** An ORM framework for Kotlin that provides a type-safe API for working with SQL databases.
- **SQLite (JDBC):** A lightweight, file-based SQL database for local persistence of player data, equipment, and resources.

## Configuration
- **SnakeYAML:** A YAML processor for the JVM. It is used to handle external configuration files (`config.yml`), allowing for runtime adjustments to game balance without re-compilation.

## Development & Testing
- **SLF4J (Simple):** Simple logging facade for basic output and debugging.
- **Kotlin Test:** Standard library for unit and integration testing.
- **MockK:** A powerful mocking library for Kotlin, used to simulate Discord events and database interactions in tests.
- **ktlint (org.jmailen.kotlinter):** A no-configuration Kotlin linter and formatter that adheres to the official Kotlin Style Guide and Android Kotlin Style Guide.
