#!/usr/bin/env bash
set -e

if [ $# -ne 6 ]; then
  echo "Wrong number of script arguments, expected $0 <repo_dir> <scala-version> <version> <targets> <maven_repo> <sbt_version?> <project_config?>, got $#: $@"
  exit 1
fi

repoDir="$1" # e.g. /tmp/shapeless
scalaVersion="$2" # e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
version="$3" # e.g. 1.0.2-communityBuild
unfilteredTargets="$4" # e.g. "com.example%foo com.example%bar"
mavenRepoUrl="$5" # e.g. https://mvn-repo/maven2/2021-05-23_1
projectConfig="$6"

targets=(${unfilteredTargets[@]})
targetExcludeFilters=$(echo $projectConfig | jq -r '.projects?.exclude? // [] | join ("|")')
if [ ! -z ${targetExcludeFilters} ]; then
  targets=( $( for target in ${unfilteredTargets[@]} ; do echo $target ; done | grep -E -v "(${targetExcludeFilters})" ) )
fi

echo '##################################'
echo Scala version: $scalaVersion
echo Disting version $version for ${#targets[@]} targets: ${targets[@]}
echo Project projectConfig: $projectConfig
echo '##################################'

cd $repoDir

millSettings=(
  -D communitybuild.version="$version"
  -D communitybuild.maven.url="$mavenRepoUrl"
  -D communitybuild.scala="$scalaVersion"
  $(echo $projectConfig | jq -r '.mill?.options? // [] | join(" ")')
)
args=(runCommunityBuild --scalaVersion "$scalaVersion" "$targets")

mill ${millSettings[@]} ${args[@]}
