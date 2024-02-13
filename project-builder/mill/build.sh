#!/usr/bin/env bash
set -e

if [ $# -ne 8 ]; then
  echo "Wrong number of script arguments, expected $0 <repo_dir> <scala-version> <version> <targets> <maven_repo> <sbt_version?> <project_config?> <extra-scalacOption?> <disabled-scalacOptions?>, got $#: $@"
  exit 1
fi

repoDir="$1"      # e.g. /tmp/shapeless
scalaVersion="$2" # e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
targets=($3)      # e.g. "com.example%foo com.example%bar"
mavenRepoUrl="$4" # e.g. https://mvn-repo/maven2/2021-05-23_1
projectConfig="$5"
extraScalacOptions="$6"
disabledScalacOption="$7"

if [[ -z $projectConfig ]]; then
  projectConfig="{}"
fi

echo '##################################'
echo Scala version: $scalaVersion
echo Targets: ${targets[@]}
echo Project projectConfig: $projectConfig
echo '##################################'

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

cd $repoDir

millSettings=(
  "--no-server"
  "--silent"
  "--disable-ticker"
  -D communitybuild.maven.url="$mavenRepoUrl"
  -D communitybuild.scala="$scalaVersion"
  -D communitybuild.appendScalacOptions="$extraScalacOptions"
  -D communitybuild.removeScalacOptions="-deprecation,-feature,-Xfatal-warnings,-Werror,$disabledScalacOption"
  $(echo $projectConfig | jq -r '.mill?.options? // [] | join(" ")' | sed "s/<SCALA_VERSION>/${scalaVersion}/g")
)

function tryBuild() {
  mill=$1
  echo "Try build using $mill"
  $mill "${millSettings[@]}" runCommunityBuild "$scalaVersion" "${projectConfig}" "${targets[@]}"
}


if [[ -f ./mill ]]; then tryBuild ./mill
else tryBuild "${scriptDir}/millw --mill-version $(cat .mill-version)"
fi
