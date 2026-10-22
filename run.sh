#!/usr/bin/env bash
#
# run.sh — local build, test, and run wrapper for broadworks-mcp.
#
# AWS deploy and image promotion are owned by MCPCICD
# (https://github.com/jpuckety/MCPCICD). This script is for laptop use
# (Maven, local HTTP/stdio servers, docker build, CDK synth) and for
# pushing this app's SSM secrets into Dev/Prod.
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
# location with ENV_FILE=... . AWS SSM push uses .env.dev / .env.prod instead.
ENV_FILE="${ENV_FILE:-${PROJECT_ROOT}/.env}"
CDK_JSON="${CDK_DIR}/cdk.json"

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

relpath() {
  local path="$1"
  if [[ "$path" == "${PROJECT_ROOT}/"* ]]; then
    printf '%s' "${path#${PROJECT_ROOT}/}"
  else
    printf '%s' "$path"
  fi
}

with_profile() {
  local profile="${1:-}"
  shift
  if [[ -n "$profile" ]]; then
    AWS_PROFILE="$profile" "$@"
  else
    "$@"
  fi
}

# Names from cdk/cdk.json context.ssm, with the stack defaults as fallback.
ssm_parameter_name() {
  local key="$1"
  local fallback="$2"
  node -e '
    const fs = require("fs");
    let value = process.argv[3];
    try {
      const ssm = (JSON.parse(fs.readFileSync(process.argv[1], "utf8")).context || {}).ssm || {};
      if (ssm[process.argv[2]]) value = String(ssm[process.argv[2]]);
    } catch (err) {}
    process.stdout.write(value);
  ' "${CDK_JSON}" "$key" "$fallback"
}

read_license_file() {
  local file="$1"
  [[ -f "$file" ]] || die "Alpaca license file not found: $(relpath "$file")"
  local value
  value="$(cat "$file")"
  value="${value%$'\n'}"
  [[ -n "$value" ]] || die "Alpaca license file is empty: $(relpath "$file")"
  printf '%s' "$value"
}

put_secure_params() {
  while [[ $# -ge 2 ]]; do
    aws ssm put-parameter \
      --name "$1" \
      --type SecureString \
      --value "$2" \
      --overwrite \
      >/dev/null
    log "Pushed $1"
    shift 2
  done
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
# SSM secrets (Dev/Prod accounts)
# --------------------------------------------------------------------------
cmd_push_secrets() {
  local env_name="$1"
  local env_slug profile env_file license_file client_id client_secret alpaca
  env_slug="$(printf '%s' "$env_name" | tr '[:upper:]' '[:lower:]')"
  require aws
  require node

  case "$env_name" in
    Dev)
      profile="${DEV_PROFILE:-${AWS_PROFILE:-}}"
      env_file="${DEV_ENV_FILE:-${PROJECT_ROOT}/.env.dev}"
      license_file="${DEV_ALPACA_LICENSE_FILE:-}"
      ;;
    Prod)
      profile="${PROD_PROFILE:-${AWS_PROFILE:-}}"
      env_file="${PROD_ENV_FILE:-${PROJECT_ROOT}/.env.prod}"
      license_file="${PROD_ALPACA_LICENSE_FILE:-}"
      ;;
    *)
      die "unknown env '${env_name}'"
      ;;
  esac

  [[ -f "$env_file" ]] || die "missing $(relpath "$env_file"). Copy GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET there (laptop .env is not pushed)."
  load_dotenv "$env_file"

  client_id="${GOOGLE_CLIENT_ID:-}"
  client_secret="${GOOGLE_CLIENT_SECRET:-}"
  [[ -n "$client_id" ]] || die "missing GOOGLE_CLIENT_ID in $(relpath "$env_file")"
  [[ -n "$client_secret" ]] || die "missing GOOGLE_CLIENT_SECRET in $(relpath "$env_file")"

  if [[ -n "$license_file" ]]; then
    alpaca="$(read_license_file "$license_file")"
  elif [[ -f "${PROJECT_ROOT}/alpaca-license.${env_slug}.txt" ]]; then
    alpaca="$(read_license_file "${PROJECT_ROOT}/alpaca-license.${env_slug}.txt")"
  elif [[ -n "${ALPACA_LICENSE_KEY:-}" ]]; then
    alpaca="${ALPACA_LICENSE_KEY}"
  else
    die "missing ${env_name} Alpaca license. Set ALPACA_LICENSE_KEY in $(relpath "$env_file") or provide alpaca-license.${env_slug}.txt"
  fi

  local id_name secret_name license_name account
  id_name="$(ssm_parameter_name googleClientId /broadworks-mcp/google-client-id)"
  secret_name="$(ssm_parameter_name googleClientSecret /broadworks-mcp/google-client-secret)"
  license_name="$(ssm_parameter_name alpacaLicenseKey /broadworks-mcp/alpaca-license-key)"
  account="$(with_profile "$profile" aws sts get-caller-identity --query Account --output text)"
  log "Pushing SSM SecureString secrets to ${env_name} account ${account}"
  log "  Google OAuth from $(relpath "$env_file")"
  log "  Alpaca license from file or ALPACA_LICENSE_KEY"
  with_profile "$profile" put_secure_params \
    "$id_name" "$client_id" \
    "$secret_name" "$client_secret" \
    "$license_name" "$alpaca"
  log "Done. BroadWorksMcpStack can now read ${id_name}, ${secret_name}, ${license_name}."
}

# --------------------------------------------------------------------------
# Usage
# --------------------------------------------------------------------------
usage() {
  cat <<'EOF'
run.sh — local build, test, and run wrapper for broadworks-mcp

Usage: ./run.sh <command> [extra args...]

AWS deploy and image promotion live in MCPCICD. SSM secrets for this app
are pushed from here.

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

SSM secrets (this app, current AWS account):
  push-secrets-dev Write /broadworks-mcp/google-client-* and alpaca-license-key
                   from .env.dev (laptop .env is not used).
  push-secrets-prod Same for Prod from .env.prod.

Environment overrides:
  JAVA_HOME         JDK 21 to use for Maven/java.
  IMAGE_NAME        Docker image tag for docker-build (default broadworks-mcp:latest).
  ENV_FILE          Path to the KEY=VALUE file (default: <repo>/.env).
  DEV_PROFILE / PROD_PROFILE   AWS CLI profile for push-secrets-* (else AWS_PROFILE).
  DEV_ENV_FILE / PROD_ENV_FILE KEY=VALUE files (default: .env.dev / .env.prod).
  DEV_ALPACA_LICENSE_FILE / PROD_ALPACA_LICENSE_FILE
                    Override alpaca-license.dev.txt / alpaca-license.prod.txt.

Configuration file:
  .env              Optional, git-ignored KEY=VALUE file at the repo root loaded
                    automatically on local commands. Values already set in the
                    environment take precedence. See .env.example for the
                    local-only variables. Pipeline / ECS do not read this file.
  .env.dev / .env.prod
                    Git-ignored files for ./run.sh push-secrets-dev|prod.

Any extra args after the command are forwarded to the underlying tool
(e.g. './run.sh test -Dtest=OpaqueTokenFactoryTest').
EOF
}

# --------------------------------------------------------------------------
# Dispatch
# --------------------------------------------------------------------------
main() {
  local cmd="${1:-help}"
  [[ $# -gt 0 ]] && shift || true

  case "${cmd}" in
    push-secrets-dev|push_secrets_dev)
      cmd_push_secrets Dev "$@"
      return
      ;;
    push-secrets-prod|push_secrets_prod)
      cmd_push_secrets Prod "$@"
      return
      ;;
    push-secrets|push_secrets)
      die "use ./run.sh push-secrets-dev or ./run.sh push-secrets-prod (laptop .env is not pushed)"
      ;;
  esac

  # Read per-developer overrides/secrets from .env (if present) before doing
  # anything else, so every command below sees them. Values already exported
  # in the environment (or passed inline) still take precedence.
  load_dotenv "${ENV_FILE}"
  # Re-honor JAVA_HOME in case it was provided via .env.
  [[ -n "${JAVA_HOME:-}" ]] && export JAVA_HOME

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
    refresh-image|refresh_image|deploy|undeploy|destroy|bootstrap)
      die "'${cmd}' was removed from this repo. Use MCPCICD for AWS bootstrap and deploy."
      ;;
    *) warn "Unknown command: ${cmd}"; echo; usage; exit 2 ;;
  esac
}

main "$@"
