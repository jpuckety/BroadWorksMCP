# syntax=docker/dockerfile:1

# ============================================================================
# Build stage: install the Alpaca toolkit JARs from lib/ into the local Maven
# repo (via the install-alpaca profile) and produce the runnable Boot jar.
# ============================================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy the pieces needed to install the local Alpaca artifacts first (better layer caching).
COPY pom.xml ./
COPY lib/ ./lib/
COPY scripts/ ./scripts/

# Copy sources and build. The install-alpaca profile installs lib/*.jar during
# the `initialize` phase, so the co.ecg:* dependencies resolve during packaging.
COPY src/ ./src/
RUN mvn -B -Pinstall-alpaca -DskipTests clean package

# ============================================================================
# Runtime stage: minimal JRE 21 image running the repackaged jar.
# ============================================================================
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the single Spring Boot fat jar produced above.
COPY --from=build /workspace/target/broadworks-mcp-*.jar /app/app.jar

# HTTP MCP transport (Streamable HTTP / SSE) + OAuth endpoints.
EXPOSE 8080

# Sensible container-aware JVM defaults; override with JAVA_OPTS as needed.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
