#!/usr/bin/env bash
set -e

if [ $# -ne 6 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repoDir="$1" # e.g. /tmp/shapeless
scalaVersion="$2" # e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
version="$3" # e.g. 1.0.2-communityBuild
targets="$4" # e.g. "com.example%foo com.example%bar"
export CB_MVN_REPO_URL="$5" # e.g. https://mvm-repo/maven2/2021-05-23_1
enforcedSbtVersion="$6"

echo '##################################'
echo Scala version: $scalaVersion
echo Disting version $version for targets: $targets
echo '##################################'

cd $repoDir

sbtVersionSetting=""
if [ -n "$6" ]; then
  sbtVersionSetting="--sbt-version $enforcedSbtVersion"
fi

sbt $sbtVersionSetting -Dcommunitybuild.version="$version" \
  \;++"$scalaVersion"! \
  \;"set every version := \"$version\"" \
  \;"set every credentials := Nil" \
  \;moduleMappings \
  \;"runBuild $targets"
