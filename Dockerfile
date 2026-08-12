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

# Non-privileged system user; the JVM never runs as root.
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin app

# Copy the single Spring Boot fat jar produced above.
COPY --from=build --chown=10001:10001 /workspace/target/broadworks-mcp-*.jar /app/app.jar

# App-owned scratch directory for the JCS disk cache (cache.ccf DiskPath=.cache/jcs, relative to
# WORKDIR). Mounted as a writable volume when the root filesystem is read-only.
RUN mkdir -p /app/.cache/jcs && chown -R 10001:10001 /app

# HTTP MCP transport (Streamable HTTP / SSE) + OAuth endpoints.
EXPOSE 8080

# curl ships with the eclipse-temurin (Ubuntu) base image, so no extra package is needed.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

USER 10001

# Sensible container-aware JVM defaults; override with JAVA_OPTS as needed.
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
