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

imageName=virtuslab/scala-community-build-builder-base
docker pull $imageName:$CACHE_FROM_TAG || true

jdkDistro=""
case $JDK_VERSION in
"8")
  jdkDistro="8.0.352-tem"
  ;;

"11")
  jdkDistro="11.0.17-tem"
  ;;

"17")
  jdkDistro="17.0.5-tem "
  ;;

"19")
  jdkDistro="19.0.1-tem"
  ;;

*)
  echo "Unexpected JDK version $JDK_VERSION"
  exit 1
  ;;
esac

docker build \
  --build-arg JDK_VERSION=${jdkDistro} \
  -t "$imageName:$TAG_NAME" \
  --cache-from "$imageName:$CACHE_FROM_TAG" \
  $scriptDir/../builder-base
