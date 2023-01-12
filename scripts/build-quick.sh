#!/usr/bin/env bash
set -e

if [ $# -ne 2 ]; then 
  echo "Wrong number of script arguments. Expected $0 <revision> <jdk_version>"
  exit 1
fi

VERSION="$1"
JDK_VERSION="$2"

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

$scriptDir/build-coordinator.sh "$VERSION" 
$scriptDir/build-compiler-builder.sh "$VERSION"
$scriptDir/build-project-builder.sh "$VERSION" "$JDK_VERSION"
$scriptDir/build-mvn-repo.sh "$VERSION"
