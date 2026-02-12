#!/usr/bin/env bash
set -euo pipefail

# build.sh (env-driven)
#
# Required env:
#   PROJECT_NAME
#   SCALA_VERSION
#   MAVEN_REPO_URL
#   EXECUTE_TESTS
#   AKKA_REPO_TOKEN
#
# Optional env:
#   EXTRA_SCALAC_OPTIONS
#   DISABLED_SCALAC_OPTIONS
#   EXTRA_LIBRARY_DEPENDENCIES
#   OPENCB_ROOT (default: /opencb)
#   TIMEOUT_SECONDS (default: 7200)

: "${PROJECT_NAME:?Missing required env PROJECT_NAME}"
: "${SCALA_VERSION:?Missing required env SCALA_VERSION}"
: "${MAVEN_REPO_URL:?Missing required env MAVEN_REPO_URL}"
: "${EXECUTE_TESTS:?Missing required env EXECUTE_TESTS}"
: "${AKKA_REPO_TOKEN:?Missing required env AKKA_REPO_TOKEN}"

EXTRA_SCALAC_OPTIONS="${EXTRA_SCALAC_OPTIONS:-}"
DISABLED_SCALAC_OPTIONS="${DISABLED_SCALAC_OPTIONS:-}"
EXTRA_LIBRARY_DEPENDENCIES="${EXTRA_LIBRARY_DEPENDENCIES:-}"
OPENCB_ROOT="${OPENCB_ROOT:-/opencb}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-7200}"

DEFAULT_CONFIG='{}'
CONFIG_FILE="${OPENCB_ROOT}/.github/workflows/buildConfig.json"
BUILD_SCRIPT="${OPENCB_ROOT}/project-builder/build-revision.sh"

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

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required but not found in PATH" >&2; exit 4; }
command -v git >/dev/null 2>&1 || { echo "ERROR: git is required but not found in PATH" >&2; exit 5; }
command -v timeout >/dev/null 2>&1 || { echo "ERROR: timeout is required but not found in PATH" >&2; exit 6; }
command -v tee >/dev/null 2>&1 || { echo "ERROR: tee is required but not found in PATH" >&2; exit 7; }

require_file "${CONFIG_FILE}"
require_exe "${BUILD_SCRIPT}"

config() {
  # Reads JSON under ."<project_name>" + <suffix>
  # Example: config ".repoUrl" -> jq -c -r '."myproj".repoUrl' buildConfig.json
  local suffix="${1:?Missing config suffix, e.g. .repoUrl or ''}"
  local jq_path
  jq_path=".\"${PROJECT_NAME}\"${suffix}"
  jq -c -r "${jq_path}" "${CONFIG_FILE}"
}

# Git config for migration patches
git config --global user.email "scala3-community-build@virtuslab.com"
git config --global user.name "Scala 3 Open Community Build"

echo "Project config: $(config '')"

cd "${OPENCB_ROOT}"

# Ensure files exist locally first
touch build-logs.txt build-summary.txt
# Assume failure unless overwritten by a successful build
echo 'failure' > build-status.txt
echo 'unknown' > build-tool.txt

export OPENCB_EXECUTE_TESTS="${EXECUTE_TESTS}"
export OPENCB_AKKA_REPO_TOKEN="${AKKA_REPO_TOKEN}"

# Run build with a hard timeout; capture both output and real exit code through PIPESTATUS.
set +e
timeout "${TIMEOUT_SECONDS}" \
  "${BUILD_SCRIPT}" \
  "$(config .project)" \
  "$(config .repoUrl)" \
  "$(config .revision)" \
  "${SCALA_VERSION}" \
  "$(config .targets)" \
  "${MAVEN_REPO_URL}" \
  "$(config ".config // ${DEFAULT_CONFIG}")" \
  "${EXTRA_SCALAC_OPTIONS}" \
  "${DISABLED_SCALAC_OPTIONS}" \
  "${EXTRA_LIBRARY_DEPENDENCIES}" \
  2>&1 | tee build-logs.txt
exit_code=${PIPESTATUS[0]}
set -e

if [[ $exit_code -eq 124 ]]; then
  echo "Build timeout after $((TIMEOUT_SECONDS / 3600)) hours" >> build-logs.txt
  echo "timeout" > build-status.txt
fi

exit "$exit_code"
