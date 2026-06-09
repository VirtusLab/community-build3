#!/usr/bin/env bash
set -euo pipefail

# Run an Open Community Build project inside the CI project-builder container.
#
# Usage:
#   ./scripts/run-in-container.sh <projectName> [scalaVersion]
#   ./scripts/run-in-container.sh --shell <projectName> [scalaVersion]
#
# Environment:
#   SKIP_BUILD_SETUP=1          Skip coordinator setup (default: run setup)
#   EXECUTE_TESTS=false         Compile only (default: true)
#   JDK_VERSION=                Override JDK from buildConfig (default: project config or 17)
#   DOCKER_PLATFORM=            Force platform, e.g. linux/amd64 on Apple Silicon
#   OPENCB_IMAGE=               Override container image
#   EXTRA_SCALAC_OPTIONS=
#   DISABLED_SCALAC_OPTIONS=
#   EXTRA_LIBRARY_DEPENDENCIES=

usage() {
  sed -n '4,17p' "$0" | sed 's/^# \{0,1\}//'
}

shellMode=false
if [[ "${1:-}" == "--shell" ]]; then
  shellMode=true
  shift
fi

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 1
fi

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
repoRoot="$(cd "$scriptDir/.." && pwd)"
cd "$repoRoot"
# shellcheck source=../project-builder/versions.sh
source "$scriptDir/../project-builder/versions.sh"

projectName="$1"
scalaVersion="${2:-}"
if [[ -z "$scalaVersion" ]]; then
  scalaVersion="$("$scriptDir/lastVersionNightly.sc")"
fi

ConfigFile="$repoRoot/.github/workflows/buildConfig.json"
DefaultConfig="{}"
DefaultJdk=17

config() {
  jq -c -r ".\"$projectName\"$*" "$ConfigFile"
}

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker is required but not found in PATH" >&2
  exit 1
fi

jdkVersion="${JDK_VERSION:-$(config .config.java.version // $DefaultJdk)}"
if [[ "$jdkVersion" == "null" || -z "$jdkVersion" ]]; then
  jdkVersion="$DefaultJdk"
fi

if [[ "$scalaVersion" =~ ^3\.[0-2]\. ]] && [[ "$jdkVersion" -ge 21 ]]; then
  echo "Force Java 17: Java 21+ is only supported since Scala 3.3.x"
  jdkVersion=17
fi

image="${OPENCB_IMAGE:-ghcr.io/virtuslab/scala-community-build-project-builder:jdk${jdkVersion}-latest}"
mavenRepoUrl="https://scala3.westeurope.cloudapp.azure.com/maven2/${scalaVersion}/"
executeTests="${EXECUTE_TESTS:-true}"
extraScalacOptions="${EXTRA_SCALAC_OPTIONS:-}"
disabledScalacOptions="${DISABLED_SCALAC_OPTIONS:-}"
extraLibraryDependencies="${EXTRA_LIBRARY_DEPENDENCIES:-}"

akkaRepoToken="dummy"
if [[ -f "$repoRoot/.secrets/akka-repo-token" ]]; then
  akkaRepoToken="$(cat "$repoRoot/.secrets/akka-repo-token")"
fi

dockerPlatform=()
if [[ -n "${DOCKER_PLATFORM:-}" ]]; then
  dockerPlatform=(--platform "$DOCKER_PLATFORM")
elif [[ "$(uname -m)" == "arm64" || "$(uname -m)" == "aarch64" ]]; then
  dockerPlatform=(--platform linux/amd64)
fi

echo "projectName: $projectName"
echo "scalaVersion: $scalaVersion"
echo "jdkVersion: $jdkVersion"
echo "image: $image"
if [[ ${#dockerPlatform[@]} -gt 0 ]]; then
  echo "dockerPlatform: ${dockerPlatform[1]}"
fi

publishScalaVersion="$(config .publishedScalaVersion)"
if [[ "$publishScalaVersion" != "null" ]] && isBinVersionGreaterThan "$publishScalaVersion" "$scalaVersion"; then
  echo "Warning: project published with Scala $publishScalaVersion - cannot guarantee it would work with older Scala version $scalaVersion"
fi

if [[ "${SKIP_BUILD_SETUP:-}" != "1" ]]; then
  scala-cli run "$scriptDir/../coordinator" -- 3 1 1 1 "$projectName" ./coordinator/configs/
fi

dockerEnv=(
  -e "PROJECT_NAME=$projectName"
  -e "SCALA_VERSION=$scalaVersion"
  -e "MAVEN_REPO_URL=$mavenRepoUrl"
  -e "EXECUTE_TESTS=$executeTests"
  -e "AKKA_REPO_TOKEN=$akkaRepoToken"
  -e "EXTRA_SCALAC_OPTIONS=$extraScalacOptions"
  -e "DISABLED_SCALAC_OPTIONS=$disabledScalacOptions"
  -e "EXTRA_LIBRARY_DEPENDENCIES=$extraLibraryDependencies"
  -e "OPENCB_ROOT=/opencb"
  -e "TIMEOUT_SECONDS=${TIMEOUT_SECONDS:-7200}"
)

dockerArgs=(
  run --rm
  "${dockerPlatform[@]}"
  -v "$repoRoot:/opencb"
  "${dockerEnv[@]}"
  "$image"
)

set +e
if [[ "$shellMode" == true ]]; then
  echo "Starting container shell (workspace mounted at /opencb)..."
  docker "${dockerArgs[@]}" bash
  exit_code=$?
else
  docker "${dockerArgs[@]}" bash /opencb/.github/actions/build-project/build.sh 2>&1 | tee build-logs.txt
  exit_code=$?
fi
set -e

if [[ "${SKIP_BUILD_SETUP:-}" != "1" ]]; then
  git restore .github/workflows/buildConfig.json || true
fi

if [[ "$shellMode" == true ]]; then
  exit "$exit_code"
fi

exec "$scriptDir/../project-builder/print-build-result.sh" "$projectName"
