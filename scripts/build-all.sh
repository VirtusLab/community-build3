#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then 
  echo "Wrong number of script arguments"
  exit 1
fi

TAG_NAME="$1"

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

$scriptDir/build-builder-base.sh "$TAG_NAME"
$scriptDir/build-quick.sh "$TAG_NAME"
