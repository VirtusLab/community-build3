#!/usr/bin/env bash
set -euo pipefail

# build-project.sh
#
# Usage:
#   build-project.sh \
#     <project_name> \
#     <scala_version> \
#     <maven_repo_url> \
#     <execute_tests> \
#     <akka_repository_token> \
#     <extra_scalac_options> \
#     <disabled_scalac_options> \
#     <extra_library_dependencies> \
#     [opencb_root] \
#     [timeout_seconds]
#
# Notes:
# - opencb_root defaults to /opencb
# - timeout_seconds defaults to 7200 (2 hours)
# - expects /opencb/.github/workflows/buildConfig.json and /opencb/project-builder/build-revision.sh in the container
# - writes/moves build-logs.txt, build-summary.txt, build-status.txt, build-tool.txt into opencb_root

PROJECT_NAME="${1:?Missing <project_name>}"
SCALA_VERSION="${2:?Missing <scala_version>}"
MAVEN_REPO_URL="${3:?Missing <maven_repo_url>}"
EXECUTE_TESTS="${4:?Missing <execute_tests>}"                 # pass "true"/"false" or whatever your builder expects
AKKA_REPO_TOKEN="${5:?Missing <akka_repository_token>}"
EXTRA_SCALAC_OPTIONS="${6:-}"
DISABLED_SCALAC_OPTIONS="${7:-}"
EXTRA_LIBRARY_DEPENDENCIES="${8:-}"
OPENCB_ROOT="${9:-/opencb}"
TIMEOUT_SECONDS="${10:-7200}"

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

# Store results (they're already in ${OPENCB_ROOT}, but keep the original move behavior safe)
mv -f build-logs.txt "${OPENCB_ROOT}/"
mv -f build-status.txt "${OPENCB_ROOT}/"
mv -f build-summary.txt "${OPENCB_ROOT}/"
mv -f build-tool.txt "${OPENCB_ROOT}/"

exit "$exit_code"
