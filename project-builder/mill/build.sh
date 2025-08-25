#!/usr/bin/env bash
set -e

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

cd $repoDir

millSettings=(
  "--no-server"
  "--silent"
  "--disable-ticker"
  -D "coursier.repositories=central|ivy2local|$mavenRepoUrl|https://repo.scala-lang.org/artifactory/maven-nightlies"
  -D "communitybuild.maven.url=$mavenRepoUrl"
  -D "communitybuild.scala=$scalaVersion"
  -D "communitybuild.appendScalacOptions=$extraScalacOptions"
  -D "communitybuild.removeScalacOptions=-deprecation,-feature,-Xfatal-warnings,-Werror,$disabledScalacOption"
  $(echo $projectConfig | jq -r '.mill?.options? // [] | join(" ")' | sed "s/<SCALA_VERSION>/${scalaVersion}/g")
)

function tryBuild() {
  mill=$1
  echo "Try build using $mill"
  # mill 0.11- does not support arg=value inputs
  $mill "${millSettings[@]}" runCommunityBuild \
    --scalaVersion "$scalaVersion" \
    --configJson "${projectConfig}" \
    --projectDir $repoDir \
    "${targets[@]}"
}

for launcher in ./millw ./mill ${scriptDir}/millw; do
  if [[ ! -f $launcher ]]; then
    continue
  fi
  chmod +x $launcher
  if $launcher resolve _ ; then
    tryBuild $launcher
    exit 0
  else
    echo "Mill launcher $launcher failed to launch, skipping"
    continue
  fi
done
echo "No working mill launcher found"
exit 1

