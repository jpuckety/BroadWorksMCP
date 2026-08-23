#!/usr/bin/env bash
#
# run.sh — local build, test, and run wrapper for broadworks-mcp.
#
# AWS deploy, image promotion, and SSM secrets are owned by MCPCICD
# (https://github.com/jpuckety/MCPCICD). This script is for laptop use:
# Maven, local HTTP/stdio servers, docker build, and optional CDK synth.
#
# Usage:
#   ./run.sh <command> [extra args...]
#
# Run "./run.sh help" for the full command list.
#
set -euo pipefail

# --------------------------------------------------------------------------
# Paths & shared configuration
# --------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}"
CDK_DIR="${PROJECT_ROOT}/cdk"

# Local, git-ignored file holding per-developer secrets (GOOGLE_CLIENT_ID /
# GOOGLE_CLIENT_SECRET, ALPACA_LICENSE_KEY). See .env.example. Override the
# location with ENV_FILE=... .
ENV_FILE="${ENV_FILE:-${PROJECT_ROOT}/.env}"

# The Maven profile that installs the Alpaca toolkit JARs from lib/ during the
# build. Applied to every Maven build target below.
ALPACA_PROFILE="install-alpaca"

# Container image name used by the `docker-build` command.
IMAGE_NAME="${IMAGE_NAME:-broadworks-mcp:latest}"

# Allow using a specific JDK 21 without changing the ambient environment, e.g.
#   JAVA_HOME=/path/to/jdk21 ./run.sh build
# If JAVA_HOME is set it is exported so Maven picks it up.
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
fi

# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------
log()  { printf '\033[1;34m[run]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[run]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[run]\033[0m %s\n' "$*" >&2; exit 1; }

require() {
  command -v "$1" >/dev/null 2>&1 || die "Required command '$1' is not installed or not on PATH."
}

# Load KEY=VALUE pairs from a .env file into the environment. Lines may be
# blank, comments (starting with '#'), or optionally prefixed with 'export '.
# Values may be optionally wrapped in single or double quotes. Variables that
# are already set in the environment take precedence and are NOT overwritten,
# so callers can still override anything on the command line.
load_dotenv() {
  local file="$1"
  [[ -f "${file}" ]] || return 0
  log "Loading environment variables from ${file#${PROJECT_ROOT}/}"
  local line key value
  while IFS= read -r line || [[ -n "${line}" ]]; do
    # Trim leading/trailing whitespace.
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    # Skip blank lines and comments.
    [[ -z "${line}" || "${line}" == \#* ]] && continue
    # Allow an optional leading 'export '.
    line="${line#export }"
    [[ "${line}" == *=* ]] || continue
    key="${line%%=*}"
    value="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"
    key="${key#"${key%%[![:space:]]*}"}"
    [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value="${value%"${value##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    if [[ ( "${value}" == \"*\" || "${value}" == \'*\' ) && ${#value} -ge 2 ]]; then
      value="${value:1:${#value}-2}"
    fi
    # Do not clobber values already present in the environment.
    if [[ -z "${!key:-}" ]]; then
      export "${key}=${value}"
    fi
  done < "${file}"
}

mvn_build() {
  require mvn
  ( cd "${PROJECT_ROOT}" && mvn -P "${ALPACA_PROFILE}" "$@" )
}

# Application environment variables loaded from .env that would otherwise leak into the test JVM and
# override the values the tests set themselves. A developer .env with PUBLIC_HOSTNAME set, for
# instance, changes the server's base URL and therefore the token audience, so tests asserting on the
# localhost defaults fail. Test runs are executed with these unset.
TEST_ISOLATED_VARS=(
  PUBLIC_HOSTNAME OIDC_ISSUER_URI GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET
  STORAGE_BACKEND SESSION_TABLE HTTP_SESSION_TABLE USER_CONFIG_TABLE KMS_KEY_ID APPLICATION_ID
  OAUTH_REDIRECT_ALLOWLIST OAUTH_ALLOW_WELL_KNOWN_CLIENTS CORS_ALLOWED_ORIGINS CORS_ENABLED
  ALPACA_LIVE ALPACA_LICENSE_KEY ALLOW_PRIVATE_NETWORK_TARGETS
)

# Same as mvn_build but with the application's runtime configuration removed from the environment
# (see TEST_ISOLATED_VARS), so the test suite always sees the defaults it asserts on.
mvn_test() {
  require mvn
  local unset_args=()
  local var
  for var in "${TEST_ISOLATED_VARS[@]}"; do
    unset_args+=(-u "${var}")
  done
  ( cd "${PROJECT_ROOT}" && env "${unset_args[@]}" mvn -P "${ALPACA_PROFILE}" "$@" )
}

# Resolve the repackaged Spring Boot jar (fails clearly if the build hasn't run).
resolve_jar() {
  local jar
  jar="$(ls -1 "${PROJECT_ROOT}"/target/broadworks-mcp-*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc' | head -1 || true)"
  [[ -n "${jar}" ]] || die "No runnable jar found in target/. Run './run.sh build' first."
  printf '%s\n' "${jar}"
}

# --------------------------------------------------------------------------
# Build / test actions
# --------------------------------------------------------------------------
cmd_install_alpaca() {
  log "Installing Alpaca toolkit JARs from lib/ into the local Maven repository..."
  "${PROJECT_ROOT}/scripts/install-alpaca.sh"
}

cmd_build() {
  log "Building the runnable Spring Boot jar (tests skipped)..."
  mvn_build -DskipTests clean package "$@"
  log "Built: $(resolve_jar)"
}

cmd_test() {
  log "Running the test suite..."
  mvn_test test "$@"
}

cmd_verify() {
  log "Building and verifying (clean verify, full test suite)..."
  mvn_test clean verify "$@"
}

cmd_clean() {
  log "Cleaning Maven build output..."
  mvn_build clean "$@"
}

# --------------------------------------------------------------------------
# Local run actions
# --------------------------------------------------------------------------
cmd_run() {
  require java
  local jar; jar="$(resolve_jar)"
  log "Starting HTTP MCP server (in-memory storage) on :8080 ..."
  # Force laptop defaults so leftover AWS/CDK values in .env cannot leak in.
  STORAGE_BACKEND=IN_MEMORY \
  PUBLIC_HOSTNAME= \
    java -jar "${jar}" "$@"
}

cmd_run_stdio() {
  require java
  local jar; jar="$(resolve_jar)"
  log "Starting stdio MCP server (in-memory storage; logs to stderr) ..."
  STORAGE_BACKEND=IN_MEMORY \
  PUBLIC_HOSTNAME= \
    java -Dspring.profiles.active=stdio -jar "${jar}" "$@"
}

# --------------------------------------------------------------------------
# Docker action
# --------------------------------------------------------------------------
cmd_docker_build() {
  require docker
  log "Building container image '${IMAGE_NAME}' from Dockerfile..."
  ( cd "${PROJECT_ROOT}" && docker build -t "${IMAGE_NAME}" -f Dockerfile . "$@" )
  log "Built image: ${IMAGE_NAME}"
}

# --------------------------------------------------------------------------
# CDK synth (local stack check; deploy is MCPCICD)
# --------------------------------------------------------------------------
cdk_run() {
  require npx
  [[ -d "${CDK_DIR}/node_modules" ]] || cmd_cdk_install
  ( cd "${CDK_DIR}" && npx cdk "$@" )
}

cmd_cdk_install() {
  require npm
  log "Installing CDK dependencies..."
  ( cd "${CDK_DIR}" && npm install --no-audit --no-fund )
}

cmd_synth() {
  log "Synthesizing the CloudFormation template..."
  cdk_run synth "$@"
}

# --------------------------------------------------------------------------
# Composite action
# --------------------------------------------------------------------------
cmd_all() {
  cmd_install_alpaca
  cmd_verify
  log "Build + tests complete."
}

# --------------------------------------------------------------------------
# Usage
# --------------------------------------------------------------------------
usage() {
  cat <<'EOF'
run.sh — local build, test, and run wrapper for broadworks-mcp

Usage: ./run.sh <command> [extra args...]

AWS deploy, image promotion, and SSM secrets live in MCPCICD, not here.

Build & test:
  install-alpaca   Install the Alpaca toolkit JARs from lib/ into the local Maven repo.
  build            Build the runnable Spring Boot jar (skips tests).
  test             Run the full test suite.
  verify           clean verify (build + full test suite).
  clean            Remove Maven build output (target/).
  all              install-alpaca + verify (full local build).

Run locally:
  run              Run the HTTP MCP server on :8080 (in-memory storage).
  run-stdio        Run the stdio MCP server (in-memory storage; logs to stderr).

Container:
  docker-build     Build the container image from the Dockerfile (IMAGE_NAME env, default broadworks-mcp:latest).

CDK (local check only):
  cdk-install      Install CDK Node dependencies (cdk/).
  synth            Synthesize the CloudFormation template.

Environment overrides:
  JAVA_HOME         JDK 21 to use for Maven/java.
  IMAGE_NAME        Docker image tag for docker-build (default broadworks-mcp:latest).
  ENV_FILE          Path to the KEY=VALUE file (default: <repo>/.env).

Configuration file:
  .env              Optional, git-ignored KEY=VALUE file at the repo root loaded
                    automatically on every command. Values already set in the
                    environment take precedence. See .env.example for the
                    local-only variables. Pipeline / ECS do not read this file.

Any extra args after the command are forwarded to the underlying tool
(e.g. './run.sh test -Dtest=OpaqueTokenFactoryTest').
EOF
}

# --------------------------------------------------------------------------
# Dispatch
# --------------------------------------------------------------------------
main() {
  # Read per-developer overrides/secrets from .env (if present) before doing
  # anything else, so every command below sees them. Values already exported
  # in the environment (or passed inline) still take precedence.
  load_dotenv "${ENV_FILE}"
  # Re-honor JAVA_HOME in case it was provided via .env.
  [[ -n "${JAVA_HOME:-}" ]] && export JAVA_HOME

  local cmd="${1:-help}"
  [[ $# -gt 0 ]] && shift || true

  case "${cmd}" in
    install-alpaca|install_alpaca) cmd_install_alpaca "$@" ;;
    build)                         cmd_build "$@" ;;
    test)                          cmd_test "$@" ;;
    verify)                        cmd_verify "$@" ;;
    clean)                         cmd_clean "$@" ;;
    all)                           cmd_all "$@" ;;
    run|run-http|run_http)         cmd_run "$@" ;;
    run-stdio|run_stdio)           cmd_run_stdio "$@" ;;
    docker-build|docker_build)     cmd_docker_build "$@" ;;
    cdk-install|cdk_install)       cmd_cdk_install "$@" ;;
    synth)                         cmd_synth "$@" ;;
    help|-h|--help)                usage ;;
    push-secrets|push_secrets|refresh-image|refresh_image|deploy|undeploy|destroy|bootstrap)
      die "'${cmd}' was removed from this repo. Use MCPCICD for AWS secrets, bootstrap, and deploy."
      ;;
    *) warn "Unknown command: ${cmd}"; echo; usage; exit 2 ;;
  esac
}

main "$@"
