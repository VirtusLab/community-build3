#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then
  echo "Wrong number of script arguments. Expected $0 <revision>"
  exit 1
fi

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
source $scriptDir/utils.sh

VERSION="$1"
CACHE_FROM_TAG="$PREV_CB_VERSION"

imageName=virtuslab/scala-community-build-compiler-builder

docker build \
  -t "$imageName:$VERSION" \
  --cache-from "$imageName:$CACHE_FROM_TAG" \
  --cache-from "$imageName:latest" \
  $scriptDir/../compiler-builder
