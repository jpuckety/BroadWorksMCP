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

# Local, git-ignored file holding per-developer environment overrides and
# secrets (e.g. GOOGLE_CLIENT_ID/SECRET, PUBLIC_HOSTNAME, KMS_KEY_ID). See
# .env.example for the full list. Override the location with ENV_FILE=... .
ENV_FILE="${ENV_FILE:-${PROJECT_ROOT}/.env}"

# The Maven profile that installs the Alpaca toolkit JARs from lib/ during the
# build. Applied to every Maven build target below.
ALPACA_PROFILE="install-alpaca"

# Container image name used by the `docker-build` command.
IMAGE_NAME="${IMAGE_NAME:-broadworks-mcp:latest}"

# Container registry (ECR) configuration used by the `refresh-image` command.
# The repository may be given either as a full URI via ECR_REPOSITORY_URI
# (<account>.dkr.ecr.<region>.amazonaws.com/<name>) or as a bare name via
# ECR_REPOSITORY (the registry host is then derived from the caller's account
# and region). IMAGE_TAG is the tag pushed to ECR and pulled by the tasks.
ECR_REPOSITORY="${ECR_REPOSITORY:-broadworks-mcp}"
ECR_REPOSITORY_URI="${ECR_REPOSITORY_URI:-}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

# ECS cluster/service running the image. `refresh-image` forces a new deployment
# on them so the tasks pull the freshly pushed image. When unset, the push still
# happens but the service refresh is skipped (with a warning).
ECS_CLUSTER="${ECS_CLUSTER:-}"
ECS_SERVICE="${ECS_SERVICE:-}"

# SSM SecureString parameter names for secrets the ECS task injects. These default
# to the same paths the CDK app reads (see cdk/lib/broadworks-mcp-stack.ts) and
# can be overridden to match a custom `ssm` CDK context.
SSM_GOOGLE_CLIENT_ID_PARAM="${SSM_GOOGLE_CLIENT_ID_PARAM:-/broadworks-mcp/google-client-id}"
SSM_GOOGLE_CLIENT_SECRET_PARAM="${SSM_GOOGLE_CLIENT_SECRET_PARAM:-/broadworks-mcp/google-client-secret}"
SSM_ALPACA_LICENSE_KEY_PARAM="${SSM_ALPACA_LICENSE_KEY_PARAM:-/broadworks-mcp/alpaca-license-key}"

# Local Alpaca license file used by push-secrets (git-ignored). Override path with
# ALPACA_LICENSE_FILE=... if needed.
ALPACA_LICENSE_FILE="${ALPACA_LICENSE_FILE:-${PROJECT_ROOT}/alpaca-license.txt}"

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
    # Must look like KEY=VALUE.
    [[ "${line}" == *=* ]] || continue
    key="${line%%=*}"
    value="${line#*=}"
    # Trim whitespace around the key.
    key="${key%"${key##*[![:space:]]}"}"
    key="${key#"${key%%[![:space:]]*}"}"
    # Only accept valid shell identifiers.
    [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    # Trim whitespace around the (still unquoted) value; quoted values keep
    # their inner whitespace because the quotes are stripped afterwards.
    value="${value%"${value##*[![:space:]]}"}"
    value="${value#"${value%%[![:space:]]*}"}"
    # Strip a single pair of surrounding quotes from the value.
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
  STORAGE_BACKEND SESSION_TABLE USER_CONFIG_TABLE KMS_KEY_ID APPLICATION_ID
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
  # No PUBLIC_HOSTNAME locally: the app defaults its base URL to http://localhost:8080.
  STORAGE_BACKEND="${STORAGE_BACKEND:-IN_MEMORY}" \
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

# Resolve the AWS region for ECR/ECS calls, falling back to the configured
# default when neither AWS_REGION nor AWS_DEFAULT_REGION is set.
ecr_region() {
  local region="${AWS_REGION:-${AWS_DEFAULT_REGION:-}}"
  if [[ -z "${region}" ]]; then
    region="$(aws configure get region 2>/dev/null || true)"
  fi
  [[ -n "${region}" ]] || die "No AWS region set. Export AWS_REGION (or configure a default region) before refreshing the image."
  printf '%s\n' "${region}"
}

# Resolve the fully-qualified ECR repository URI. Prefers ECR_REPOSITORY_URI
# when given; otherwise derives <account>.dkr.ecr.<region>.amazonaws.com from the
# caller's identity and appends the bare ECR_REPOSITORY name.
resolve_ecr_repository_uri() {
  if [[ -n "${ECR_REPOSITORY_URI}" ]]; then
    printf '%s\n' "${ECR_REPOSITORY_URI}"
    return 0
  fi
  local region account
  region="$(ecr_region)"
  account="$(aws sts get-caller-identity --query Account --output text 2>/dev/null || true)"
  [[ -n "${account}" && "${account}" != "None" ]] || die "Unable to determine the AWS account id (aws sts get-caller-identity). Check your AWS credentials."
  printf '%s.dkr.ecr.%s.amazonaws.com/%s\n' "${account}" "${region}" "${ECR_REPOSITORY}"
}

# Build the image, push it to ECR, and force a new ECS deployment so the running
# tasks pull the freshly pushed tag.
cmd_refresh_image() {
  require docker
  require aws

  local region repo_uri registry repo_name remote
  region="$(ecr_region)"
  repo_uri="$(resolve_ecr_repository_uri)"
  registry="${repo_uri%%/*}"
  repo_name="${repo_uri##*/}"
  remote="${repo_uri}:${IMAGE_TAG}"

  # 1. Build the image locally (reuses IMAGE_NAME).
  cmd_docker_build

  # 2. Authenticate Docker to the ECR registry.
  log "Logging in to ECR registry ${registry}..."
  aws ecr get-login-password --region "${region}" \
    | docker login --username AWS --password-stdin "${registry}"

  # Create the repository on first push so a fresh account works out of the box.
  if ! aws ecr describe-repositories --region "${region}" --repository-names "${repo_name}" >/dev/null 2>&1; then
    log "Creating ECR repository '${repo_name}'..."
    aws ecr create-repository --region "${region}" --repository-name "${repo_name}" >/dev/null
  fi

  # 3. Tag and push the image to ECR.
  log "Tagging ${IMAGE_NAME} as ${remote}..."
  docker tag "${IMAGE_NAME}" "${remote}"
  log "Pushing ${remote}..."
  docker push "${remote}"

  # 4. Refresh the ECS service so tasks roll over to the new image.
  if [[ -n "${ECS_CLUSTER}" && -n "${ECS_SERVICE}" ]]; then
    log "Forcing a new deployment of ECS service '${ECS_SERVICE}' on cluster '${ECS_CLUSTER}'..."
    aws ecs update-service \
      --region "${region}" \
      --cluster "${ECS_CLUSTER}" \
      --service "${ECS_SERVICE}" \
      --force-new-deployment \
      >/dev/null
    log "Deployment triggered. Tasks will roll over to ${remote}."
  else
    warn "ECS_CLUSTER and/or ECS_SERVICE not set — skipping the service refresh."
    warn "Set them (e.g. ECS_CLUSTER=... ECS_SERVICE=... ./run.sh refresh-image) to force a rolling deployment."
  fi

  log "Done: built and pushed ${remote}."
}

# --------------------------------------------------------------------------
# Secrets (SSM) action
# --------------------------------------------------------------------------
# Push a single SSM SecureString parameter, overwriting any existing value.
# Encrypts with the default SSM KMS key (aws/ssm); pass --region when AWS_REGION
# is set so the parameter lands in the expected account/region.
put_secure_param() {
  local name="$1" value="$2"
  local region_args=()
  [[ -n "${AWS_REGION:-}" ]] && region_args=(--region "${AWS_REGION}")
  aws ssm put-parameter \
    "${region_args[@]+"${region_args[@]}"}" \
    --name "${name}" \
    --type SecureString \
    --value "${value}" \
    --overwrite \
    >/dev/null
  log "Pushed ${name}"
}

# Load the Alpaca license from ALPACA_LICENSE_FILE (default: repo-root alpaca-license.txt).
# Supports multi-line file content (unlike .env KEY=VALUE). Trims a single trailing newline.
load_alpaca_license_from_file() {
  local file="${ALPACA_LICENSE_FILE}"
  [[ -f "${file}" ]] || die "Alpaca license file not found: ${file#${PROJECT_ROOT}/} (set ALPACA_LICENSE_FILE=... to override)."
  local value
  # Preserve internal newlines; strip one trailing newline from the file if present.
  value="$(cat "${file}")"
  value="${value%$'\n'}"
  [[ -n "${value}" ]] || die "Alpaca license file is empty: ${file#${PROJECT_ROOT}/}"
  printf '%s' "${value}"
}

# Push Google OAuth secrets from .env and the Alpaca license from alpaca-license.txt into
# SSM as SecureString parameters so the deployed ECS task (ecs.Secret.fromSsmParameter)
# picks them up. GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET come from .env (or the environment);
# ALPACA_LICENSE_KEY for the push is read from ALPACA_LICENSE_FILE (default alpaca-license.txt).
cmd_push_secrets() {
  require aws
  local missing=()
  [[ -n "${GOOGLE_CLIENT_ID:-}" ]]     || missing+=(GOOGLE_CLIENT_ID)
  [[ -n "${GOOGLE_CLIENT_SECRET:-}" ]] || missing+=(GOOGLE_CLIENT_SECRET)
  if [[ ${#missing[@]} -gt 0 ]]; then
    die "Missing required value(s): ${missing[*]}. Set them in ${ENV_FILE#${PROJECT_ROOT}/} (or the environment) before pushing."
  fi
  local alpaca_license
  alpaca_license="$(load_alpaca_license_from_file)"
  log "Pushing secrets into SSM${AWS_REGION:+ (region ${AWS_REGION})}..."
  log "  Google OAuth from ${ENV_FILE#${PROJECT_ROOT}/} (or environment)"
  log "  Alpaca license from ${ALPACA_LICENSE_FILE#${PROJECT_ROOT}/}"
  put_secure_param "${SSM_GOOGLE_CLIENT_ID_PARAM}"     "${GOOGLE_CLIENT_ID}"
  put_secure_param "${SSM_GOOGLE_CLIENT_SECRET_PARAM}" "${GOOGLE_CLIENT_SECRET}"
  put_secure_param "${SSM_ALPACA_LICENSE_KEY_PARAM}"   "${alpaca_license}"
  log "Done. Deploy (or restart the service) so the task picks up the new values."
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
  cdk_run synth "${args[@]+"${args[@]}"}" "$@"
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
  cdk_run deploy "${args[@]+"${args[@]}"}" "$@"
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
  refresh-image    Build the image, push it to ECR, and force a new ECS deployment
                   (ECR_REPOSITORY/ECR_REPOSITORY_URI, IMAGE_TAG, ECS_CLUSTER, ECS_SERVICE).

Secrets (AWS SSM):
  push-secrets     Push Google OAuth secrets from .env (GOOGLE_CLIENT_ID,
                   GOOGLE_CLIENT_SECRET) and the Alpaca license from
                   alpaca-license.txt into SSM as SecureString parameters.

Deploy (AWS CDK):
  cdk-install      Install CDK Node dependencies (cdk/).
  synth            Synthesize the CloudFormation template.
  bootstrap        Bootstrap the CDK environment (one-time per account/region).
  deploy [certArn] Deploy the BroadWorksMcpStack (builds the image as a CDK asset).
  undeploy         Destroy the BroadWorksMcpStack.

Environment overrides:
  JAVA_HOME         JDK 21 to use for Maven/java.
  IMAGE_NAME        Docker image tag for docker-build (and the local build refresh-image pushes).
  ECR_REPOSITORY    ECR repository name for refresh-image (default broadworks-mcp); the
                    registry host is derived from your AWS account/region.
  ECR_REPOSITORY_URI  Full ECR repository URI for refresh-image; overrides ECR_REPOSITORY
                    (<account>.dkr.ecr.<region>.amazonaws.com/<name>).
  IMAGE_TAG         Tag pushed to ECR by refresh-image (default latest).
  ECS_CLUSTER, ECS_SERVICE
                    ECS cluster/service refreshed by refresh-image via a forced
                    new deployment (skipped with a warning when unset).
  CERTIFICATE_ARN   ACM certificate ARN for the HTTPS ALB listener (deploy/synth).
  AWS_REGION        AWS region targeted by push-secrets (passed as --region).
  SSM_GOOGLE_CLIENT_ID_PARAM, SSM_GOOGLE_CLIENT_SECRET_PARAM,
  SSM_ALPACA_LICENSE_KEY_PARAM
                    SSM parameter names for push-secrets (defaults
                    /broadworks-mcp/google-client-id, .../google-client-secret,
                    and .../alpaca-license-key).
  ALPACA_LICENSE_FILE
                    Path to the Alpaca license file read by push-secrets
                    (default: <repo>/alpaca-license.txt).
  PUBLIC_HOSTNAME   Public DNS hostname; the base URL is built as https://<hostname>
                    (unset locally -> http://localhost:8080).
  STORAGE_BACKEND, ...  Passed through to the local `run` command.

Configuration file:
  .env              Optional, git-ignored KEY=VALUE file at the repo root loaded
                    automatically on every command. Values already set in the
                    environment take precedence. Override its path with ENV_FILE=...
                    See .env.example for the supported variables.

Any extra args after the command are forwarded to the underlying tool
(e.g. './run.sh test -Dtest=OpaqueTokenFactoryTest' or './run.sh deploy --require-approval never').
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
    refresh-image|refresh_image)   cmd_refresh_image "$@" ;;
    push-secrets|push_secrets)     cmd_push_secrets "$@" ;;
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
