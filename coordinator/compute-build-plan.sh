#!/usr/bin/env bash
set -e

if [ $# -ne 1 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

scalaVersion="$1" #3.0.0-RC3

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

cd $scriptDir && sbt --sbt-version $SBT_VERSION "runMain storeDependenciesBasedBuildPlan $scalaVersion"