#!/usr/bin/env bash
set -e

if [ $# -ne 2 ]; then 
  echo "Wrong number of script arguments. Expected $0 <revision> <jdk_version>"
  exit 1
fi

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh 

VERSION="$1"
JDK_VERSION="$2"
TAG_NAME=$(buildTag $VERSION $JDK_VERSION)

docker build -t virtuslab/scala-community-build-project-builder:"$TAG_NAME" \
  --build-arg BASE_IMAGE="virtuslab/scala-community-build-builder-base:$TAG_NAME" $scriptDir/../project-builder
