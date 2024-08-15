#!/usr/bin/env bash
set -e

if [ $# -ne 2 ]; then
  echo "Wrong number of script arguments. Expected $0 <revision> <jdk_version>"
  exit 1
fi

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
source $scriptDir/utils.sh

VERSION="$1"
JDK_VERSION="$2"
TAG_NAME=$(buildTag $VERSION $JDK_VERSION)
CACHE_FROM_TAG=$(buildTag "$PREV_CB_VERSION" $JDK_VERSION)

imageName=virtuslab/scala-community-build-project-builder

docker build \
  --build-arg BASE_IMAGE="virtuslab/scala-community-build-builder-base:$TAG_NAME" \
  -t "$imageName:$TAG_NAME" \
  --cache-from "$imageName:$CACHE_FROM_TAG" \
  --cache-from "$imageName:$(buildTag latest $JDK_VERSION)" \
  --cache-from "$imageName:$(buildTag v0.3.13 $JDK_VERSION)" \
  $scriptDir/../project-builder
