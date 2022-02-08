#!/usr/bin/env bash

set -e

if [ $# -ne 2 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repoDir="$1" # e.g. /tmp/shapeless
enforcedSbtVersion="$2" # e.g. '1.5.5' or empty ''

# Check if using a sbt with a supported version

if [ ! -f $repoDir/project/build.properties ]; then
  echo "'project/build.properties' is missing"
  exit 1
fi

if [ -n "$enforcedSbtVersion" ]; then
  sbtVersion="$enforcedSbtVersion"
else
  sbtVersion=$(cat $repoDir/project/build.properties | grep sbt.version= | awk -F= '{ print $2 }')
fi

function parseSemver() {
  local prefixSufix=(`echo ${1/-/ }`)
  local prefix=${prefixSufix[0]}
  local suffix=${prefixSufix[1]}
  local numberParts=(`echo ${prefix//./ }`)
  local major=${numberParts[0]}
  local minor=${numberParts[1]}
  local patch=${numberParts[2]}
  echo "$major $minor $patch $suffix"
}

sbtSemVerParts=(`echo $(parseSemver "$sbtVersion")`)
sbtMajor=${sbtSemVerParts[0]}
sbtMinor=${sbtSemVerParts[1]}
sbtPatch=${sbtSemVerParts[2]}

if [ "$sbtMajor" -lt 1 ] || ([ "$sbtMajor" -eq 1 ] && [ "$sbtMinor" -lt 5 ]) || ([ "$sbtMajor" -eq 1 ] && [ "$sbtMinor" -eq 5 ] && [ "$sbtPatch" -lt 5 ]); then
  echo "Sbt version $sbtVersion is not supported. Use sbt 1.5.5. or newer"
  exit 1
fi

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

# Register command for setting up version, for more info check command impl comments
echo -e "\ncommands += CommunityBuildPlugin.setPublishVersion\n" >> $repoDir/build.sbt

ln -fs $scriptDir/CommunityBuildPlugin.scala $repoDir/project/CommunityBuildPlugin.scala
