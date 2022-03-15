#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

TAG_NAME="$1"
CACHE_FROM_TAG="$PREV_CB_VERSION"

imageName=virtuslab/scala-community-build-mvn-repo

docker pull $imageName:$CACHE_FROM_TAG || true
docker build \
  -t "$imageName:$TAG_NAME" \
  --cache-from "$imageName:$CACHE_FROM_TAG" \
  $scriptDir/../mvn-repo
