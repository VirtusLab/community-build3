#!/usr/bin/env bash
set -e

if [ $# -ne 7 ]; then
  echo "Wrong number of script arguments, expected $0 <repo_dir> <scala-version> <version> <targets> <maven_repo> <sbt_version?> <project_config?>, got $#: $@"
  exit 1
fi

repoDir="$1" # e.g. /tmp/shapeless
scalaVersion="$2" # e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
version="$3" # e.g. 1.0.2-communityBuild
unfilteredTargets="$4" # e.g. "com.example%foo com.example%bar"
export CB_MVN_REPO_URL="$5" # e.g. https://mvn-repo/maven2/2021-05-23_1
enforcedSbtVersion="$6"
projectConfig="$7"

targets=${unfilteredTargets[@]}
targetExcludeFilters=$(echo $projectConfig | jq -r '.sbt.exclude? // [] | join ("|")')
if [ ! -z ${targetExcludeFilters} ]; then
  targets=( $( for target in ${unfilteredTargets[@]} ; do echo $target ; done | grep -E -v "(${targetExcludeFilters})" ) )
fi

echo '##################################'
echo Scala version: $scalaVersion
echo Disting version $version for ${#targets[@]} targets: ${targets[@]}
echo Project projectConfig: $projectConfig
echo '##################################'

cd $repoDir

sbtVersionSetting=""
if [ -n "$enforcedSbtVersion" ]; then
  sbtVersionSetting="--sbt-version $enforcedSbtVersion"
fi

sbtSettings=(
  -Dcommunitybuild.version="$version"
  "-J-Xmx5G"
  $(echo $projectConfig | jq -r '.sbt.options? // [] | join(" ")')
)
customCommands=$(echo "$projectConfig" | jq -r '.sbt?.commands? // [] | join("; ")')

# Use `setPublishVersion` instead of `every version`, as it might overrte Jmh/Jcstress versions
sbt $sbtVersionSetting ${sbtSettings[@]} \
  "++$scalaVersion"! \
  "setPublishVersion $version" \
  "set every credentials := Nil" \
  "$customCommands" \
  "moduleMappings" \
  "runBuild $targets"
