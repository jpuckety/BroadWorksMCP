#!/usr/bin/env bash
#
# Installs the Alpaca toolkit JARs (supplied under lib/) into the local Maven
# repository so that broadworks-mcp can depend on them as normal Maven
# dependencies (required for spring-boot-maven-plugin repackage and the
# Fargate container image).
#
# The JARs are installed with MINIMAL generated POMs (no transitive
# dependencies) on purpose: the original alpaca-* artifacts were built against
# Spring Boot 2.7 / javax and we must NOT drag that stack onto the Boot 3.x /
# jakarta classpath. Any runtime companions the toolkit genuinely needs are
# declared explicitly in the project pom.xml.
#
# NOTE: alpaca-server-*.jar (the full Spring Boot 2.7 application) is kept in
# lib/ for reference ONLY and is intentionally NOT installed.
#
# Usage:
#   ./scripts/install-alpaca.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LIB_DIR="${PROJECT_ROOT}/lib"

GROUP_ID="co.ecg"
VERSION="12.2.0-RELEASE"

# artifactId -> jar file name (build-suffixed file names normalized to VERSION)
declare -a ARTIFACTS=(
  "alpaca-commons:alpaca-commons-12.2.0-RELEASE.jar"
  "alpaca-model:alpaca-model-12.2.0-RELEASE.jar"
  "alpaca-core:alpaca-core-12.2.0-RELEASE-26.jar"
  "alpaca-library:alpaca-library-12.2.0-RELEASE-26.jar"
)

echo "Installing Alpaca toolkit JARs from ${LIB_DIR} into the local Maven repository..."

for entry in "${ARTIFACTS[@]}"; do
  artifact_id="${entry%%:*}"
  jar_file="${entry##*:}"
  jar_path="${LIB_DIR}/${jar_file}"

  if [[ ! -f "${jar_path}" ]]; then
    echo "ERROR: expected JAR not found: ${jar_path}" >&2
    echo "       Place the Alpaca toolkit JARs under lib/ and retry." >&2
    exit 1
  fi

  echo "  -> ${GROUP_ID}:${artifact_id}:${VERSION}  (${jar_file})"
  mvn -q org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
    -Dfile="${jar_path}" \
    -DgroupId="${GROUP_ID}" \
    -DartifactId="${artifact_id}" \
    -Dversion="${VERSION}" \
    -Dpackaging=jar \
    -DgeneratePom=true
done

echo "Done. Alpaca toolkit artifacts are now available at ${GROUP_ID}:*:${VERSION}."
