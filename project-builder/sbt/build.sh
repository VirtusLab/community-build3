#!/usr/bin/env bash
set -e
set -o pipefail

if [ $# -ne 9 ]; then
  echo "Wrong number of script arguments, expected $0 <repo_dir> <scala-version> <version> <targets> <maven_repo> <project_config?> <extraScalacOpts?> <removeScalacOpts?> <extraDeps?>, got $#: $@"
  exit 1
fi

repoDir="$1"                # e.g. /tmp/shapeless
scalaVersion="$2"           # e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
version="$3"                # e.g. 1.0.2-communityBuild
targets=($4)                # e.g. "com.example%foo com.example%bar"
export CB_MVN_REPO_URL="$5" # e.g. https://mvn-repo/maven2/2021-05-23_1
projectConfig="$6"
extraScalacOptions="$7"
disabledScalacOption="$8"
extraLibraryDeps="$9"

if [[ -z "$projectConfig" ]]; then
  projectConfig="{}"
fi

echo '##################################'
echo Scala version: $scalaVersion
echo Disting version $version for ${#targets[@]} targets: ${targets[@]}
echo Project projectConfig: $projectConfig
echo '##################################'

if [[ ! -z $extraScalacOptions ]]; then
  echo "Using extra scalacOptions: ${extraScalacOptions}"
fi

if [[ ! -z $disabledScalacOption ]]; then
  echo "Filtering out scalacOptions: ${disabledScalacOption}"
fi

cd $repoDir

# GithHub actions workers have maximally 7GB of RAM
memorySettings=("-J-Xmx7G" "-J-Xms4G" "-J-Xss8M")

# Don't set version if not publishing
setVersionCmd="setPublishVersion $version"
if [[ -z $version ]]; then
  setVersionCmd=""
fi

sbtSettings=(
  --batch
  --verbose
  "-Dcommunitybuild.version=$version"
  "-Dcommunitybuild.scala=$scalaVersion"
  "-Dcommunitybuild.project.dependencies.add=$extraLibraryDeps"
  ${memorySettings[@]}
  $(echo $projectConfig | jq -r '.sbt.options? // [] | join(" ")' | sed "s/<SCALA_VERSION>/${scalaVersion}/g")
)
customCommands=$(echo "$projectConfig" | jq -r '.sbt?.commands // [] | join ("; ")')
targetsString="${targets[@]}"
logFile=build.log

shouldRetry=false
forceScalaVersion=false
appendScalacOptions="${extraScalacOptions}"
removeScalacOptions="${disabledScalacOption}"

function runSbt() {
  # Use `setPublishVersion` instead of `every version`, as it might overrte Jmh/Jcstress versions
  # set every ... might lead to restoring original version changed in setPublishVersion
  setScalaVersionCmd="++$scalaVersion"
  if [[ "$forceScalaVersion" == "true" ]]; then
    echo "Would force Scala version $scalaVersion"
    setScalaVersionCmd="++$scalaVersion!"
  fi
  tq='"""'
  sbt ${sbtSettings[@]} \
    "setCrossScalaVersions $scalaVersion" \
    "$setScalaVersionCmd -v" \
    "mapScalacOptions \"$appendScalacOptions\" \"$removeScalacOptions\"" \
    "set every credentials := Nil" \
    "$setVersionCmd" \
    "$customCommands" \
    "moduleMappings" \
    "runBuild ${scalaVersion} ${tq}${projectConfig}${tq} $targetsString" | tee $logFile
}

function checkLogsForRetry() {
  # Retry only when given modes were not tried yet
  shouldRetry=false
  # Failed to switch version
  if [ "$forceScalaVersion" = false ]; then
    if grep -q 'Switch failed:' "$logFile"; then
      forceScalaVersion=true
      shouldRetry=true
    elif grep -q 'Module mapping missing:' "$logFile" && grep -q -e 'moduleIds: .*_2\.1[1-3]' "$logFile"; then
      # Incorrect mappings using Scala 2.13
      forceScalaVersion=true
      shouldRetry=true
    fi
  fi
}

retry=0
maxRetries=1 # 1 retry for each: missing mappings (force scala version)

function retryBuild() {
  while [[ $retry -lt $maxRetries ]]; do
    checkLogsForRetry
    if [ "$shouldRetry" = true ]; then
      retry+=1
      echo "Retrying build, retry $retry/$maxRetries, force Scala version:$forceScalaVersion, enable migration:$enableMigrationMode"
      runSbt && exit 0
    else
      echo "Build failed, not retrying."
      exit 1
    fi
  done
  echo "Exhausted retries limit"
  exit 1
}

runSbt || retryBuild
