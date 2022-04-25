#!/usr/bin/env bash
set -e
set -o pipefail

if [ $# -ne 7 ]; then
  echo "Wrong number of script arguments, expected $0 <repo_dir> <scala-version> <version> <targets> <maven_repo> <sbt_version?> <project_config?>, got $#: $@"
  exit 1
fi

repoDir="$1"                # e.g. /tmp/shapeless
scalaVersion="$2"           # e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
version="$3"                # e.g. 1.0.2-communityBuild
targets=($4)                # e.g. "com.example%foo com.example%bar"
export CB_MVN_REPO_URL="$5" # e.g. https://mvn-repo/maven2/2021-05-23_1
projectConfig="$7"

if [[ -z "$projectConfig" ]]; then
  projectConfig="{}"
fi

echo '##################################'
echo Scala version: $scalaVersion
echo Disting version $version for ${#targets[@]} targets: ${targets[@]}
echo Project projectConfig: $projectConfig
echo '##################################'

cd $repoDir

sbtSettings=(
  --batch
  --no-colors
  --verbose
  -Dcommunitybuild.version="$version"
  $(echo $projectConfig | jq -r '.sbt.options? // [] | join(" ")' | sed "s/<SCALA_VERSION>/${scalaVersion}/g")
)
customCommands=$(echo "$projectConfig" | jq -r '.sbt?.commands // [] | join ("; ")')
targetsString="${targets[@]}"
logFile=build.log

function runSbt() {
  # Use `setPublishVersion` instead of `every version`, as it might overrte Jmh/Jcstress versions
  # set every ... might lead to restoring original version changed in setPublishVersion
  forceScalaVersion=$1
  setScalaVersionCmd="++$scalaVersion"
  if [[ "$forceScalaVersion" == "forceScala" ]]; then
    setScalaVersionCmd="++$scalaVersion!"
  fi
  tq='"""'
  sbt ${sbtSettings[@]} \
    "$setScalaVersionCmd -v" \
    "set every credentials := Nil" \
    "$customCommands" \
    "setPublishVersion $version" \
    "moduleMappings" \
    "runBuild ${scalaVersion} ${tq}${projectConfig}${tq} $targetsString" | tee $logFile
}

runSbt "no force" || {
  shouldRetry=0
  # Failed to switch version
  if grep -q 'Switch failed:' "$logFile"; then
    shouldRetry=1
  # Incorrect mappings usng Scala 2.13
  elif grep -q 'Module mapping missing:' "$logFile" && grep -q -e 'moduleIds: .*_2\.1[1-3]' "$logFile"; then
    shouldRetry=1
  fi

  if [[ "$shouldRetry" -eq 1 ]]; then
    echo "Retrying build with forced Scala version"
    runSbt "forceScala"
  else
    echo "Build failed, not retrying with forced Scala version"
    exit 1
  fi
}
