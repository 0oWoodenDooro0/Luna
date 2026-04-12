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

### 1. Log Rotation Failure in Logback 1.5.x
**Problem:** Using `FixedWindowRollingPolicy` combined with `SizeBasedTriggeringPolicy` can sometimes fail to trigger rotation in Logback 1.5.x, causing the log file to grow indefinitely beyond the `maxFileSize`.

**Solution:** Use `SizeAndTimeBasedRollingPolicy` instead. Even if you only care about size, this policy is more robust in recent Logback versions.

```xml
<rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
    <fileNamePattern>data/logs/luna-json.%d{yyyy-MM-dd}.%i.log.zip</fileNamePattern>
    <maxFileSize>1MB</maxFileSize>
    <maxHistory>1</maxHistory>
    <totalSizeCap>2MB</totalSizeCap>
</rollingPolicy>
```

**Debugging Tip:** If logs aren't rotating, add a status listener to `logback.xml` to see internal errors:
`<statusListener class="ch.qos.logback.core.status.OnConsoleStatusListener" />`

### 2. Testing SizeAndTimeBasedRollingPolicy in Kotlin
**Problem:** In Logback 1.5.x, the `maxFileSize` field in `SizeAndTimeBasedRollingPolicy` is package-private. Attempting to access it directly in Kotlin tests (e.g., `policy.maxFileSize`) will result in a compilation error.

**Solution:** Since there is no public getter like `getMaxFileSize()` in some versions of Logback 1.5, avoid asserting on the internal state of the policy. Instead, rely on **Functional/Integration Testing** (e.g., a stress test that actually writes enough data to trigger rotation and then checks the file system).

### 3. Testing Structured Logging
Using `ListAppender<ILoggingEvent>` to verify `StructuredArguments` (like `kv("key", value)`) is difficult because they don't appear in the `message` string but in the `argumentArray`.

**Solution:** For critical verification, check the actual log file output or use a more sophisticated log capture utility that understands Logstash markers.

## Dependency Versions
- **Logback Classic:** `1.5.32`
- **Logstash Logback Encoder:** `9.0` (Supports structured JSON logging)
