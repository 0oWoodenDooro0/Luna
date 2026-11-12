# syntax=docker/dockerfile:1

# Stage 1: Build stage
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

# Copy gradle wrapper and build files first to leverage Docker layer caching
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY libs/socialpeek/build.gradle.kts ./libs/socialpeek/
COPY libs/config/build.gradle.kts ./libs/config/
COPY libs/mcp-data/build.gradle.kts ./libs/mcp-data/
COPY mcp-server/build.gradle.kts ./mcp-server/

# Make gradlew executable and download the Gradle distribution, retrying transient
# CDN failures (timeouts / HTTP 504) so a single flaky download doesn't fail the build
RUN chmod +x gradlew && \
    for attempt in 1 2 3 4 5; do \
        ./gradlew --version --no-daemon && break; \
        [ "$attempt" -eq 5 ] && exit 1; \
        echo "Gradle distribution download failed (attempt $attempt), retrying..."; \
        sleep $((attempt * 5)); \
    done

# Fetch dependencies
RUN ./gradlew dependencies --no-daemon || true

# Copy source code
COPY src ./src
COPY libs ./libs
COPY mcp-server ./mcp-server
COPY .env.example ./.env.example

# Build the fat jar and mcp-server distribution
RUN ./gradlew jar :mcp-server:installDist --no-daemon

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Create non-root user and data directories
RUN useradd -m -u 1000 luna && \
    mkdir -p /app/data /app/.data && \
    chown -R luna:luna /app

# Copy fat jar and mcp-server from builder stage
COPY --from=builder --chown=luna:luna /app/build/libs/Luna-*.jar /app/Luna.jar
COPY --from=builder --chown=luna:luna /app/mcp-server/build/install/mcp-server /app/mcp-server
COPY --chown=luna:luna .env.example /app/.env.example
COPY --chown=luna:luna scripts/ /app/scripts/

RUN chmod +x /app/scripts/*.sh

USER luna

# Expose default HTTP ports
EXPOSE 8080 8081

# Expose volume for persistent SQLite database and logs
VOLUME ["/app/.data", "/app/data"]

ENTRYPOINT ["java", "-jar", "/app/Luna.jar"]
