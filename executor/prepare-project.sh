#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repoDir="$1" # e.g. /tmp/shapeless

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

ln -fs $scriptDir/CommunityBuildPlugin.scala $repoDir/project/CommunityBuildPlugin.scala
