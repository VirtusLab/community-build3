#!/usr/bin/env bash
set -euo pipefail

# index.sh (env-driven)
#
# Required env:
#   PROJECT_NAME
#   SCALA_VERSION
#   ELASTIC_USERNAME
#   ELASTIC_PASSWORD
#   CONTAINER_REGISTRY_TOKEN_SECRET
#   AKKA_REPOSITORY_TOKEN_SECRET
#   BUILD_ID
#   BUILD_URL
#
# Optional env:
#   OPENCB_ROOT (default: /opencb)

: "${PROJECT_NAME:?Missing required env PROJECT_NAME}"
: "${SCALA_VERSION:?Missing required env SCALA_VERSION}"
: "${ELASTIC_USERNAME:?Missing required env ELASTIC_USERNAME}"
: "${ELASTIC_PASSWORD:?Missing required env ELASTIC_PASSWORD}"
: "${CONTAINER_REGISTRY_TOKEN_SECRET:?Missing required env CONTAINER_REGISTRY_TOKEN_SECRET}"
: "${AKKA_REPOSITORY_TOKEN_SECRET:?Missing required env AKKA_REPOSITORY_TOKEN_SECRET}"
: "${BUILD_ID:?Missing required env BUILD_ID}"
: "${BUILD_URL:?Missing required env BUILD_URL}"

OPENCB_ROOT="${OPENCB_ROOT:-/opencb}"
DATA_ENDPOINT='https://scala3.westeurope.cloudapp.azure.com/data'
CONFIG_FILE="${OPENCB_ROOT}/.github/workflows/buildConfig.json"

require_file() {
  local f="$1"
  if [[ ! -f "$f" ]]; then
    echo "ERROR: Required file not found: $f" >&2
    exit 2
  fi
}

require_exe() {
  local f="$1"
  if [[ ! -x "$f" ]]; then
    echo "ERROR: Required executable not found or not executable: $f" >&2
    exit 3
  fi
}

config() {
  # Reads JSON under ."<project_name>" + <suffix>, matching original logic.
  # Example: config ".version" -> jq -c -r '."myproj".version' buildConfig.json
  local suffix="${1:?Missing config suffix like .version}"
  local jq_path
  jq_path=".\"${PROJECT_NAME}\"${suffix}"
  jq -c -r "${jq_path}" "${CONFIG_FILE}"
}

echo "Indexing build results..."
cd "${OPENCB_ROOT}"

# Validate inputs/files used later
require_file "${CONFIG_FILE}"
require_file "${OPENCB_ROOT}/build-logs.txt"
require_file "${OPENCB_ROOT}/build-status.txt"
require_file "${OPENCB_ROOT}/build-summary.txt"
require_file "${OPENCB_ROOT}/build-tool.txt"
require_exe "${OPENCB_ROOT}/project-builder/feed-elastic.sh"
require_file "${OPENCB_ROOT}/project-builder/redact-logs.scala"

# Ensure jq exists (used by config())
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required but not found in PATH" >&2; exit 4; }
command -v scala-cli >/dev/null 2>&1 || { echo "ERROR: scala-cli is required but not found in PATH" >&2; exit 5; }

# Remove ASCII coloring and redact secrets using Scala
scala-cli run "${OPENCB_ROOT}/project-builder/redact-logs.scala" --server=false -- \
  "${OPENCB_ROOT}/build-logs.txt" \
  "${OPENCB_ROOT}/build-logs-redacted.txt" \
  "${ELASTIC_PASSWORD}" \
  "${CONTAINER_REGISTRY_TOKEN_SECRET}" \
  "${AKKA_REPOSITORY_TOKEN_SECRET}"

index_exit=0
for attempt in 1 2 3; do
  set +e
  "${OPENCB_ROOT}/project-builder/feed-elastic.sh" \
    "${DATA_ENDPOINT}" \
    "${PROJECT_NAME}" \
    "$(cat "${OPENCB_ROOT}/build-status.txt")" \
    "$(date --iso-8601=seconds)" \
    "${OPENCB_ROOT}/build-summary.txt" \
    "${OPENCB_ROOT}/build-logs-redacted.txt" \
    "$(config .version)" \
    "${SCALA_VERSION}" \
    "${BUILD_ID}" \
    "${BUILD_URL}" \
    "$(cat "${OPENCB_ROOT}/build-tool.txt")"
  index_exit=$?
  set -e

  if [[ $index_exit -eq 0 ]]; then
    break
  elif [[ $attempt -lt 3 ]]; then
    echo "Indexing failed, would retry"
    sleep $((attempt * 5))
  fi
done

if [[ $index_exit -ne 0 ]]; then
  # Emit a GitHub Actions workflow command warning
  echo "::warning title=Indexing failure::Indexing results of ${PROJECT_NAME} failed"
fi
