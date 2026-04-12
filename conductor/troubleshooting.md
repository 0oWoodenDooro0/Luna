# Troubleshooting & Lessons Learned

This document records common errors and their solutions encountered during development to avoid repeating the same mistakes.

## Exposed (v1) Framework

### 1. Correct Import Paths for Logging & Transactions
In Exposed v1, many core classes and functions are located in the `org.jetbrains.exposed.v1.core` package, not `jdbc`.

- **SqlLogger:** `org.jetbrains.exposed.v1.core.SqlLogger`
- **Transaction:** `org.jetbrains.exposed.v1.core.Transaction`
- **StatementContext:** `org.jetbrains.exposed.v1.core.statements.StatementContext`
- **expandArgs:** `org.jetbrains.exposed.v1.core.statements.expandArgs`
- **addLogger:** `org.jetbrains.exposed.v1.jdbc.addLogger` (This one is often in `jdbc` or available via `Transaction` extension)
- **Table / insert / update / selectAll:** 
    - `org.jetbrains.exposed.v1.core.Table`
    - `org.jetbrains.exposed.v1.jdbc.insert`
    - `org.jetbrains.exposed.v1.jdbc.update`
    - `org.jetbrains.exposed.v1.jdbc.selectAll`

**Solution:** Always verify if a class belongs to `.core` or `.jdbc` when using Exposed v1.

### 2. Missing Dependencies
When writing database tests (e.g., using H2), ensure the following are in `build.gradle.kts`:
- `testImplementation("com.h2database:h2:<version>")`
- `implementation("org.jetbrains.exposed:exposed-core:<version>")`
- `implementation("org.jetbrains.exposed:exposed-jdbc:<version>")`

## Logback Configuration

### 1. Single File with Strict Size Limit
To maintain exactly one active log file and at most one backup (compressed) with a strict size limit, use `SizeAndTimeBasedRollingPolicy` for better reliability in Logback 1.5.x:

```xml
<appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>data/logs/luna-json.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <fileNamePattern>data/logs/luna-json.%d{yyyy-MM-dd}.%i.log.zip</fileNamePattern>
        <maxFileSize>1MB</maxFileSize>
        <maxHistory>1</maxHistory>
        <totalSizeCap>2MB</totalSizeCap>
    </rollingPolicy>
    <encoder class="net.logstash.logback.encoder.LogstashEncoder" />
</appender>
```

**Note:** `SizeAndTimeBasedRollingPolicy` handles both size and time, but by setting `maxHistory` to 1 and `maxFileSize` to 1MB, it effectively acts as a size-based rotation with minimal history.

### 2. Testing Structured Logging
Using `ListAppender<ILoggingEvent>` to verify `StructuredArguments` (like `kv("key", value)`) is difficult because they don't appear in the `message` string but in the `argumentArray`.

**Solution:** For critical verification, check the actual log file output or use a more sophisticated log capture utility that understands Logstash markers.

## Dependency Versions
- **Logback Classic:** `1.5.32`
- **Logstash Logback Encoder:** `9.0` (Supports structured JSON logging)
