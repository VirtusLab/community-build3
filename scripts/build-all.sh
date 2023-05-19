#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then
  echo "Wrong number of script arguments. Expected <revision>"
  exit 1
fi

VERSION="$1"
export PREV_CB_VERSION="v0.2.4"

javaDefault=11
javaAccessoryVersions=(8 17 19)
scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

if [[ ! -z "${BUILD_ONLY_DEFAULT_JDK}" ]]; then
  echo "Skipping build of accessory JDK images"
else
  for javaVersion in "${javaAccessoryVersions[@]}"; do
    $scriptDir/build-builder-base.sh "$VERSION" "$javaVersion"
    $scriptDir/build-project-builder.sh "$VERSION" "$javaVersion"
  done
fi

# Compiler builder (build in build-quick) build accessory version images before default one
$scriptDir/build-builder-base.sh "$VERSION" "$javaDefault"
$scriptDir/build-quick.sh "$VERSION" "$javaDefault"
