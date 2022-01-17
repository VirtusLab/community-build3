#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then 
  echo "Wrong number of script arguments. Expected <revision>"
  exit 1
fi

VERSION="$1"
JDK_VERSIONS=(8 11 17)
scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

for jdkVersion in "${JDK_VERSIONS[@]}"; do
  $scriptDir/build-builder-base.sh "$VERSION" "$jdkVersion"
  $scriptDir/build-quick.sh "$VERSION" "$jdkVersion"
done
