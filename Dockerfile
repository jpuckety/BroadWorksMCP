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

# Install the local Alpaca toolkit JARs (lib/*.jar) into the local Maven
# repository in a SEPARATE Maven invocation, BEFORE packaging.
#
# This MUST be its own `mvn` run that stops at the `initialize` phase. Running
# the install-alpaca profile together with `package` in a single invocation
# fails, because Maven resolves the co.ecg:* compile-scope dependencies for the
# reactor before the profile's initialize-phase install-file executions have
# populated the local repo, and a clean container has no other source for them
# (hence "Could not find artifact co.ecg:alpaca-*:jar:... in central").
RUN mvn -B -Pinstall-alpaca initialize

# Copy sources and build. The co.ecg:* dependencies now resolve from the local
# Maven repository populated above.
COPY src/ ./src/
RUN mvn -B -DskipTests clean package

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
