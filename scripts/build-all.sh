#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then
  echo "Wrong number of script arguments. Expected <revision>"
  exit 1
fi

VERSION="$1"
export PREV_CB_VERSION="v0.0.7"

javaDefault=11
javaAccessoryVersions=(8 17)
scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

for javaVersion in "${javaAccessoryVersions[@]}"; do
  $scriptDir/build-builder-base.sh "$VERSION" "$javaVersion"
  $scriptDir/build-project-builder.sh "$VERSION" "$javaVersion"
done

# Compiler builder (build in build-quick) build accessory version images before default one
$scriptDir/build-builder-base.sh "$VERSION" "$javaDefault"
$scriptDir/build-quick.sh "$VERSION" "$javaDefault"
