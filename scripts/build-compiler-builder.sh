#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then
  echo "Wrong number of script arguments. Expected $0 <revision>"
  exit 1
fi

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
source $scriptDir/utils.sh

VERSION="$1"
# Use base image with Java 11
TAG_NAME=$(buildTag $VERSION 11)
CACHE_FROM_TAG="$PREV_CB_VERSION"

imageName=virtuslab/scala-community-build-compiler-builder
docker pull $imageName:$CACHE_FROM_TAG || true

docker build \
  --build-arg BASE_IMAGE="virtuslab/scala-community-build-builder-base:$TAG_NAME" \
  -t "$imageName:$VERSION" \
  --cache-from "$imageName:$CACHE_FROM_TAG" \
  $scriptDir/../compiler-builder
