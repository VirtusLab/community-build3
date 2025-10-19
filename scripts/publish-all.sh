#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then
  echo "Wrong number of script arguments. Expected <revision>"
  exit 1
fi

VERSION="$1"

javaVersions=(17 21 25)
scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

BUILDER_BASE=virtuslab/scala-community-build-builder-base
PROJECT_BUILDER=virtuslab/scala-community-build-project-builder
# MVN_REPO=virtuslab/scala-community-build-mvn-repo

# JDK-specifc images
for image in $BUILDER_BASE $PROJECT_BUILDER; do
  for javaVersion in "${javaVersions[@]}"; do
    JDK_VERSION=jdk$javaVersion-$VERSION
    JDK_LATEST=jdk$javaVersion-latest
    docker tag $image:$JDK_VERSION $image:$JDK_LATEST
    for tag in $JDK_VERSION $JDK_LATEST; do
      docker push $image:$tag
    done

  done
done

# Single-JDK images
# $MVN_REPO is not build anymore
# for image in $MVN_REPO; do
#   docker tag $image:$VERSION $image:latest
#   for tag in $VERSION latest; do
#     docker push $image:$tag
#   done
# done
