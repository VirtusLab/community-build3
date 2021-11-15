#!/usr/bin/env bash
set -e

if [ $# -ne 4 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repoUrl="$1" # e.g. https://github.com/lampepfl/dotty.git
rev="$2" # e.g. master
scalaVersion="$3" # e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
mvnRepoUrl="$4" # e.g. https://mvn-repo/maven2/2021-05-23_1

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

$scriptDir/checkout.sh "$repoUrl" "$rev" repo
$scriptDir/build.sh repo "$scalaVersion" "$mvnRepoUrl"
