#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then 
  echo "Wrong number of script arguments"
  exit 1
fi

TAG_NAME="$1"

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

$scriptDir/build-coordinator.sh "$TAG_NAME"
$scriptDir/build-compiler-builder.sh "$TAG_NAME"
$scriptDir/build-project-builder.sh "$TAG_NAME"
$scriptDir/build-mvn-repo.sh "$TAG_NAME"
$scriptDir/build-sample-repos.sh
