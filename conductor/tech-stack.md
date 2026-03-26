# Technology Stack: Luna

## Language & Runtime
- **Kotlin (JVM):** The primary programming language used for the bot's development. It provides a modern and concise syntax for JVM-based applications.

## Discord Library
- **Kord:** A Kotlin-first Discord API wrapper. It is used to interact with the Discord API, handling commands, events, and interactions.

## Database & Persistence
- **SQLite:** A lightweight, serverless, and self-contained SQL database engine used for local storage of player data, hunts, and progression.
- **Exposed ORM:** A lightweight SQL library on top of the JDBC driver for Kotlin. It is used to interact with the SQLite database in a type-safe and idiomatic way.

## Architecture
- **Layered Architecture:** The project follows a strict separation of concerns with the following layers:
  - **Command Layer:** Handles Discord interactions (via Kord) and delegates business logic to Services.
  - **Service Layer:** Contains the core business logic and orchestrates data access via Repositories.
  - **Repository Layer:** Provides a generic and unified interface for data persistence using the Exposed ORM.
- **Generic Repository Pattern:** A unified `IRepository<T, ID>` interface is used for all data access, reducing boilerplate and ensuring consistency.

## Logging & Utilities
- **SLF4J:** A simple logging facade for Java, used for standardized logging throughout the application.

## Testing
- **Kotlin Test:** The built-in Kotlin testing framework used for unit and integration tests.
- **MockK:** A powerful mocking library for Kotlin, used to mock objects and verify behavior in tests.
