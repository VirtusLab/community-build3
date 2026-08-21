#!/usr/bin/env bash
set -e
set -o pipefail

if [ $# -ne 7 ]; then
  echo "Wrong number of script arguments, expected $0 <repo_dir> <scala-version> <targets> <maven_repo> <sbt_version?> <project_config?> <extra-scalacOption?> <disabled-scalacOptions?>, got $#: $@"
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
# shellcheck source=../retry-utils.sh
source "$scriptDir/../retry-utils.sh"

cd $repoDir

millSettings=(
  "--no-server"
  "--silent"
  "--disable-ticker"
  -D "coursier.repositories=central|ivy2local|$mavenRepoUrl|https://repo.scala-lang.org/artifactory/maven-nightlies"
  -D "communitybuild.maven.url=$mavenRepoUrl"
  -D "communitybuild.scala=$scalaVersion"
  -D "communitybuild.migrating=${OPENCB_MIGRATING:-false}"
  -D "communitybuild.appendScalacOptions=$extraScalacOptions"
  -D "communitybuild.removeScalacOptions=-deprecation,-feature,-Xfatal-warnings,-Werror,$disabledScalacOption"
  $(echo $projectConfig | jq -r '.mill?.options? // [] | join(" ")' | sed "s/<SCALA_VERSION>/${scalaVersion}/g")
)

logFile="build.log"
resolveLogFile="mill-resolve.log"

function runBuild() {
  mill=$1
  rm -rf $repoDir/out
  # mill 0.11- does not support arg=value inputs
  $mill "${millSettings[@]}" runCommunityBuild \
    --scalaVersion "$scalaVersion" \
    --configJson "${projectConfig}" \
    --projectDir $repoDir \
    "${targets[@]}"
}

function tryBuild() {
  mill=$1
  echo "Try build using $mill"
  opencb_run_retrying_rate_limits "$logFile" runBuild "$mill"
}

for launcher in ./millw ./mill ${scriptDir}/millw; do
  if [[ ! -f $launcher ]]; then
    continue
  fi
  chmod +x $launcher
  if opencb_run_retrying_rate_limits "$resolveLogFile" $launcher resolve _ > /dev/null ; then
    tryBuild $launcher
    exit 0
  else
    echo "Mill launcher $launcher failed to launch, skipping"
    continue
  fi
done
echo "No working mill launcher found"
exit 1

