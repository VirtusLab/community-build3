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
#   OPENCB_CACHE_MOUNTS=0       Disable mounting dependency caches into the container
#   OPENCB_CACHE_DIR=           Use repo-local cache dir instead of $HOME (avoids root-owned files in ~)
#   OPENCB_IVY2_CACHE=            Override host ivy2 cache path (default: $HOME/.ivy2)
#   OPENCB_SBT_CACHE=             Override host sbt cache path (default: $HOME/.sbt)
#   OPENCB_COURSIER_CACHE=        Override host coursier cache path
#   OPENCB_MILL_CACHE=            Override host mill cache path (default: $HOME/.cache/mill)
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

opencb_host_coursier_cache() {
  if [[ -n "${OPENCB_COURSIER_CACHE:-}" ]]; then
    echo "${OPENCB_COURSIER_CACHE}"
  elif [[ -n "${OPENCB_CACHE_DIR:-}" ]]; then
    echo "${OPENCB_CACHE_DIR}/coursier"
  elif [[ "$(uname -s)" == Darwin && -d "${HOME}/Library/Caches/Coursier" ]]; then
    echo "${HOME}/Library/Caches/Coursier"
  elif [[ -d "${HOME}/.cache/coursier" ]]; then
    echo "${HOME}/.cache/coursier"
  else
    echo "${HOME}/.cache/coursier"
  fi
}

opencb_docker_cache_mounts() {
  cache_mounts=()
  if [[ "${OPENCB_CACHE_MOUNTS:-1}" == "0" ]]; then
    return
  fi

  if [[ -n "${OPENCB_CACHE_DIR:-}" ]]; then
    ivy2_host="${OPENCB_CACHE_DIR}/ivy2"
    sbt_host="${OPENCB_CACHE_DIR}/sbt"
    coursier_host="${OPENCB_CACHE_DIR}/coursier"
    mill_host="${OPENCB_CACHE_DIR}/mill"
    scalacli_host="${OPENCB_CACHE_DIR}/scalacli"
  else
    ivy2_host="${OPENCB_IVY2_CACHE:-${HOME}/.ivy2}"
    sbt_host="${OPENCB_SBT_CACHE:-${HOME}/.sbt}"
    coursier_host="$(opencb_host_coursier_cache)"
    mill_host="${OPENCB_MILL_CACHE:-${HOME}/.cache/mill}"
    scalacli_host="${HOME}/.cache/scalacli"
  fi

  declare -a cache_pairs=(
    "$ivy2_host:/root/.ivy2"
    "$sbt_host:/root/.sbt"
    "$coursier_host:/root/.cache/coursier"
    "$mill_host:/root/.cache/mill"
    "$scalacli_host:/root/.cache/scalacli"
  )

  echo "cacheMounts:"
  for pair in "${cache_pairs[@]}"; do
    host_path="${pair%%:*}"
    container_path="${pair#*:}"
    mkdir -p "$host_path"
    cache_mounts+=(-v "$host_path:$container_path")
    echo "  $host_path -> $container_path"
  done
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

cache_mounts=()
opencb_docker_cache_mounts

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
  "${cache_mounts[@]}"
  "${dockerEnv[@]}"
  "$image"
)

set +e
if [[ "$shellMode" == true ]]; then
  echo "Starting container shell (workspace mounted at /opencb)..."
  docker "${dockerArgs[@]}" bash
  exit_code=$?
else
  docker "${dockerArgs[@]}" bash /opencb/.github/actions/build-project/build.sh
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
