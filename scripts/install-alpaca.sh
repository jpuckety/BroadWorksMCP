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

# artifactId:version:jar-file-name
# (the ecg-licensing runtime companion is versioned independently of the toolkit)
declare -a ARTIFACTS=(
  "alpaca-commons:12.2.0-RELEASE:alpaca-commons-12.2.0-RELEASE.jar"
  "alpaca-model:12.2.0-RELEASE:alpaca-model-12.2.0-RELEASE.jar"
  "alpaca-core:12.2.0-RELEASE:alpaca-core-12.2.0-RELEASE-26.jar"
  "alpaca-library:12.2.0-RELEASE:alpaca-library-12.2.0-RELEASE-26.jar"
  "ecg-licensing:6.2.0-RELEASE:ecg-licensing-6.2.0-RELEASE.jar"
)

echo "Installing Alpaca toolkit JARs from ${LIB_DIR} into the local Maven repository..."

for entry in "${ARTIFACTS[@]}"; do
  IFS=':' read -r artifact_id version jar_file <<< "${entry}"
  jar_path="${LIB_DIR}/${jar_file}"

  if [[ ! -f "${jar_path}" ]]; then
    echo "ERROR: expected JAR not found: ${jar_path}" >&2
    echo "       Place the Alpaca toolkit JARs under lib/ and retry." >&2
    exit 1
  fi

  echo "  -> ${GROUP_ID}:${artifact_id}:${version}  (${jar_file})"
  mvn -q org.apache.maven.plugins:maven-install-plugin:3.1.2:install-file \
    -Dfile="${jar_path}" \
    -DgroupId="${GROUP_ID}" \
    -DartifactId="${artifact_id}" \
    -Dversion="${version}" \
    -Dpackaging=jar \
    -DgeneratePom=true
done

echo "Done. Alpaca toolkit artifacts are now available under ${GROUP_ID}:*."
