#!/usr/bin/env bash

set -e

if [ $# -ne 3 ]; then
  echo "Wrong number of script arguments, expected 3 $0 <repo_dir> <scala_version> <publish_version>, got $#: $@"
  exit 1
fi

repoDir="$1" # e.g. /tmp/shapeless
scalaVersion="$2" # e.g. 3.1.2-RC1
publishVersion="$3" # version of the project

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Rename build.sc to build.scala - Scalafix does ignore .sc files
# Use scala 3 dialect to allow for top level defs
cp repo/build.sc repo/build.scala \
  && scalafix \
    --rules file:${scriptDir}/scalafix/rules/src/main/scala/fix/Scala3CommunityBuildMillAdapter.scala \
    --files repo/build.scala \
    --stdout \
    --syntactic \
    --scala-version 3.1.0 > repo/build.sc \
  && rm repo/build.scala 

ln -fs $scriptDir/MillCommunityBuild.sc $repoDir/MillCommunityBuild.sc