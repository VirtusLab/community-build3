#!/usr/bin/env bash

set -e

if [ $# -ne 4 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repoDir="$1"            # e.g. /tmp/shapeless
enforcedSbtVersion="$2" # e.g. '1.5.5' or empty ''
scalaVersion="$3"
projectConfig="$4"

# Check if using a sbt with a supported version

buildPropsFile="${repoDir}/project/build.properties"
if [ ! -f "${buildPropsFile}" ]; then
  echo "'project/build.properties' is missing"
  mkdir ${repoDir}/project || true
  echo "sbt.version=${enforcedSbtVersion}" >$buildPropsFile
fi

sbtVersion=$(cat "${buildPropsFile}" | grep sbt.version= | awk -F= '{ print $2 }')

function parseSemver() {
  local prefixSufix=($(echo ${1/-/ }))
  local prefix=${prefixSufix[0]}
  local suffix=${prefixSufix[1]}
  local numberParts=($(echo ${prefix//./ }))
  local major=${numberParts[0]}
  local minor=${numberParts[1]}
  local patch=${numberParts[2]}
  echo "$major $minor $patch $suffix"
}

sbtSemVerParts=($(echo $(parseSemver "$sbtVersion")))
sbtMajor=${sbtSemVerParts[0]}
sbtMinor=${sbtSemVerParts[1]}
sbtPatch=${sbtSemVerParts[2]}

if [[ "$sbtMajor" -lt 1 ]] ||
  ([[ "$sbtMajor" -eq 1 ]] && [[ "$sbtMinor" -lt 5 ]]) ||
  ([[ "$sbtMajor" -eq 1 ]] && [[ "$sbtMinor" -eq 5 ]] && [[ "$sbtPatch" -lt 5 ]]); then
  echo "Sbt version $sbtVersion is not supported, minimal supported version is 1.5.5"
  if [ -n "$enforcedSbtVersion" ]; then
    echo "Enforcing usage of sbt in version ${enforcedSbtVersion}"
    sed -i -E "s/(sbt.version=).*/\1${enforcedSbtVersion}/" "${buildPropsFile}"
  else
    exit 1
  fi
fi

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

# Register utility commands, for more info check command impl comments
echo -e "\ncommands ++= CommunityBuildPlugin.commands\n" >>$repoDir/build.sbt

# Base64 is used to mitigate spliting json by whitespaces
for elem in $(echo "${projectConfig}" | jq -r '.sourcePatches // [] | .[] | @base64'); do
  function field() {
    echo ${elem} | base64 --decode | jq -r ${1}
  }
  replaceWith=$(echo "$(field '.replaceWith')" | sed "s/<SCALA_VERSION>/${scalaVersion}/")
  path=$(field '.path')
  pattern=$(field '.pattern')
  set -x
  # Cannot determinate did sed script was applied, so perform two ops each time
  sed -i "s/$pattern/$replaceWith/" "$repoDir/$path" || true
  sed -i -E "s/$pattern/$replaceWith/" "$repoDir/$path" || true
  set +x
done

ln -fs $scriptDir/../shared/CommunityBuildCore.scala $repoDir/project/CommunityBuildCore.scala
ln -fs $scriptDir/CommunityBuildPlugin.scala $repoDir/project/CommunityBuildPlugin.scala

# Project dependencies
# https://github.com/shiftleftsecurity/codepropertygraph#building-the-code
cd $repoDir
git lfs pull || true
## scala-debug adapter
echo "StrictHostKeyChecking no" >> ~/.ssh/config
(git submodule sync && git submodule update --init --recursive) || true
