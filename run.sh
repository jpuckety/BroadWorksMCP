#!/usr/bin/env bash
#
# run.sh — convenience wrapper around the common broadworks-mcp build and
# deploy/undeploy actions.
#
# This is a thin dispatcher over the same commands documented in README.md
# (Maven with the `install-alpaca` profile for the app, and AWS CDK for the
# infrastructure). It exists so the day-to-day workflow is a single command.
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

# The Maven profile that installs the Alpaca toolkit JARs from lib/ during the
# build. Applied to every Maven build target below.
ALPACA_PROFILE="install-alpaca"

# Container image name used by the `docker-build` command.
IMAGE_NAME="${IMAGE_NAME:-broadworks-mcp:latest}"

# Optional ACM certificate ARN for the HTTPS ALB listener. May also be passed
# on the command line (see `deploy`). Falls back to the env var the CDK app
# already understands.
CERTIFICATE_ARN="${CERTIFICATE_ARN:-}"

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

mvn_build() {
  require mvn
  ( cd "${PROJECT_ROOT}" && mvn -P "${ALPACA_PROFILE}" "$@" )
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
  mvn_build test "$@"
}

cmd_verify() {
  log "Building and verifying (clean verify, full test suite)..."
  mvn_build clean verify "$@"
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
  STORAGE_BACKEND="${STORAGE_BACKEND:-IN_MEMORY}" \
  PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-http://localhost:8080}" \
    java -jar "${jar}" "$@"
}

cmd_run_stdio() {
  require java
  local jar; jar="$(resolve_jar)"
  log "Starting stdio MCP server (in-memory storage; logs to stderr) ..."
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
# CDK (deploy / undeploy) actions
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

# Build the certificate context arg (if a cert ARN is available) plus any
# extra args the caller passed through.
cdk_cert_args() {
  if [[ -n "${CERTIFICATE_ARN}" ]]; then
    printf -- '-c\ncertificateArn=%s\n' "${CERTIFICATE_ARN}"
  fi
}

cmd_synth() {
  log "Synthesizing the CloudFormation template..."
  local args=(); while IFS= read -r a; do [[ -n "$a" ]] && args+=("$a"); done < <(cdk_cert_args)
  cdk_run synth "${args[@]}" "$@"
}

cmd_bootstrap() {
  log "Bootstrapping the CDK environment (one-time per account/region)..."
  cdk_run bootstrap "$@"
}

cmd_deploy() {
  # First positional arg may be a certificate ARN for convenience:
  #   ./run.sh deploy arn:aws:acm:...:certificate/...
  if [[ "${1:-}" == arn:aws:acm:* ]]; then
    CERTIFICATE_ARN="$1"; shift
  fi
  if [[ -z "${CERTIFICATE_ARN}" ]]; then
    warn "No certificateArn provided — the ALB will listen on HTTP only (development)."
    warn "Provide one via: ./run.sh deploy arn:aws:acm:... | CERTIFICATE_ARN=arn... ./run.sh deploy"
  fi
  log "Deploying the BroadWorksMcpStack (builds the Docker image as a CDK asset)..."
  local args=(); while IFS= read -r a; do [[ -n "$a" ]] && args+=("$a"); done < <(cdk_cert_args)
  cdk_run deploy "${args[@]}" "$@"
}

cmd_undeploy() {
  log "Destroying the BroadWorksMcpStack..."
  cdk_run destroy "$@"
}

# --------------------------------------------------------------------------
# Composite action
# --------------------------------------------------------------------------
cmd_all() {
  cmd_install_alpaca
  cmd_verify
  log "Build + tests complete. Use './run.sh deploy' to provision AWS infrastructure."
}

# --------------------------------------------------------------------------
# Usage
# --------------------------------------------------------------------------
usage() {
  cat <<'EOF'
run.sh — build and deploy/undeploy wrapper for broadworks-mcp

Usage: ./run.sh <command> [extra args...]

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

Deploy (AWS CDK):
  cdk-install      Install CDK Node dependencies (cdk/).
  synth            Synthesize the CloudFormation template.
  bootstrap        Bootstrap the CDK environment (one-time per account/region).
  deploy [certArn] Deploy the BroadWorksMcpStack (builds the image as a CDK asset).
  undeploy         Destroy the BroadWorksMcpStack.

Environment overrides:
  JAVA_HOME         JDK 21 to use for Maven/java.
  IMAGE_NAME        Docker image tag for docker-build.
  CERTIFICATE_ARN   ACM certificate ARN for the HTTPS ALB listener (deploy/synth).
  STORAGE_BACKEND, PUBLIC_BASE_URL, ...  Passed through to the local `run` command.

Any extra args after the command are forwarded to the underlying tool
(e.g. './run.sh test -Dtest=OpaqueTokenFactoryTest' or './run.sh deploy --require-approval never').
EOF
}

# --------------------------------------------------------------------------
# Dispatch
# --------------------------------------------------------------------------
main() {
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
    bootstrap)                     cmd_bootstrap "$@" ;;
    deploy)                        cmd_deploy "$@" ;;
    undeploy|destroy)              cmd_undeploy "$@" ;;
    help|-h|--help)                usage ;;
    *) warn "Unknown command: ${cmd}"; echo; usage; exit 2 ;;
  esac
}

main "$@"
