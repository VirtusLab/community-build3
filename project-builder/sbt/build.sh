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
enforcedSbtVersion="$6"
projectConfig="$7"

# Wait until mvn-repo is reachable, frequently few first requests might fail
# especially in cli immediately after starting minikube
for i in {1..10}; do
  if errMsg=$(curl $CB_MVN_REPO_URL 2>&1); then
    break
  else
    echo "Waiting until mvn-repo is reachable..."
    sleep 1
  fi
done

echo '##################################'
echo Scala version: $scalaVersion
echo Disting version $version for ${#targets[@]} targets: ${targets[@]}
echo Project projectConfig: $projectConfig
echo '##################################'

cd $repoDir
if [ -n "$enforcedSbtVersion" ]; then
  # Overwrite properties file, sbt thin client cannot take --sbt-version param
  echo -e "sbt.version=$enforcedSbtVersion\n" >project/build.properties
fi

sbtSettings=(
  --batch
  --no-colors
  -Dcommunitybuild.version="$version"
  $(echo $projectConfig | jq -r '.sbt.options? // [] | join(" ")')
)
customCommands=$(echo "$projectConfig" | jq -r '.sbt?.commands // [] | join ("; ")')
targetsString="${targets[@]}"
logFile=build.log

function runSbt() {
  # Use `setPublishVersion` instead of `every version`, as it might overrte Jmh/Jcstress versions
  forceScalaVersion=$1
  setScalaVersionCmd="++$scalaVersion"
  if [[ "$forceScalaVersion" == "forceScala" ]]; then
    setScalaVersionCmd="++$scalaVersion!"
  fi

  sbt ${sbtSettings[@]} \
    "$setScalaVersionCmd -v" \
    "setPublishVersion $version" \
    "set every credentials := Nil" \
    "$customCommands" \
    "moduleMappings" \
    "runBuild ${scalaVersion} \"${projectConfig}\" $targetsString" | tee $logFile
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
