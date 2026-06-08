#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then
  echo "Wrong number of script arguments. Expected <revision>"
  exit 1
fi

VERSION="$1"
MVN_REPO=ghcr.io/virtuslab/scala-community-build-mvn-repo

docker tag "$MVN_REPO:$VERSION" "$MVN_REPO:latest"
for tag in "$VERSION" latest; do
  docker push "$MVN_REPO:$tag"
done
