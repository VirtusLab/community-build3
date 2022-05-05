#!/usr/bin/env bash

set -e

if [ $# -ne 4 ]; then
  echo "Wrong number of script arguments, expected 3 $0 <repo_dir> <scala_version> <publish_version>, got $#: $@"
  exit 1
fi

repoDir="$1" # e.g. /tmp/shapeless
scalaVersion="$2" # e.g. 3.1.2-RC1
publishVersion="$3" # version of the project
projectConfig="$4" 

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

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


# Rename build.sc to build.scala - Scalafix does ignore .sc files
# Use scala 3 dialect to allow for top level defs
cp repo/build.sc repo/build.scala \
  && scalafix \
    --rules file:${scriptDir}/scalafix/rules/src/main/scala/fix/Scala3CommunityBuildMillAdapter.scala \
    --files repo/build.scala \
    --stdout \
    --syntactic \
    --settings.Scala3CommunityBuildMillAdapter.targetScalaVersion "$scalaVersion" \
    --settings.Scala3CommunityBuildMillAdapter.targetPublishVersion "$publishVersion" \
    --scala-version 3.1.0 > repo/build.sc \
  && rm repo/build.scala 

ln -fs $scriptDir/../shared/CommunityBuildCore.scala $repoDir/CommunityBuildCore.sc
ln -fs $scriptDir/MillCommunityBuild.sc $repoDir/MillCommunityBuild.sc