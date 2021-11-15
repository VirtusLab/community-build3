#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then 
  echo "Wrong number of script arguments"
  exit 1
fi

TAG_NAME="$1"

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

docker build -t virtuslab/scala-community-build-builder-base:"$TAG_NAME" $scriptDir/../builder-base
