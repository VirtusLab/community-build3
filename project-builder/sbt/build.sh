#!/usr/bin/env bash
set -e
set -o pipefail

if [ $# -ne 8 ]; then
  echo "Wrong number of script arguments, expected $0 <repo_dir> <scala-version> <version> <targets> <maven_repo> <project_config?>, got $#: $@"
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

# + "" would replace null with empty string
requestedMemoryMb=$(echo $projectConfig | jq -r '.memoryRequestMb // empty')
memorySettings=()
if [[ ! -z "$requestedMemoryMb" ]]; then
  size="${requestedMemoryMb}m"
  memorySettings=("-J-Xmx${size}" "-J-Xms${size}")
fi

# Don't set version if not publishing
setVersionCmd="setPublishVersion $version"
if [[ -z $version ]]; then
  setVersionCmd=""
fi

sbtSettings=(
  --batch
  --no-colors
  --verbose
  -Dcommunitybuild.version="$version"
  -Dcommunitybuild.extra-scalac-options="$extraScalacOptions"
  -Dcommunitybuild.disabled-scalac-options="$disabledScalacOption"
  ${memorySettings[@]}
  $(echo $projectConfig | jq -r '.sbt.options? // [] | join(" ")' | sed "s/<SCALA_VERSION>/${scalaVersion}/g")
)
customCommands=$(echo "$projectConfig" | jq -r '.sbt?.commands // [] | join ("; ")')
targetsString="${targets[@]}"
logFile=build.log

shouldRetry=false
forceScalaVersion=false
enableMigrationMode=false
sourceVersionToUseForMigration=""

function runSbt() {
  # Use `setPublishVersion` instead of `every version`, as it might overrte Jmh/Jcstress versions
  # set every ... might lead to restoring original version changed in setPublishVersion
  setScalaVersionCmd="++$scalaVersion"
  if [[ "$forceScalaVersion" == "true" ]]; then
    echo "Would force Scala version $scalaVersion"
    setScalaVersionCmd="++$scalaVersion!"
  fi
  enableMigrationModeCmd=""
  if [[ "$enableMigrationMode" == "true" ]]; then
    echo "Would enable migration mode $scalaVersion"
    enableMigrationModeCmd="enableMigrationMode"
  fi
  tq='"""'
  sbt ${sbtSettings[@]} \
    "setCrossScalaVersions $scalaVersion" \
    "$setScalaVersionCmd -v" \
    "set every credentials := Nil" \
    "$customCommands" \
    "$setVersionCmd" \
    "$enableMigrationModeCmd $sourceVersionToUseForMigration" \
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

  if [ "$enableMigrationMode" = false ]; then
    if grep -P --max-count=1 'can be rewritten automatically under -rewrite -source ([\w\.]+-migration)' "$logFile"; then
      # Don't pass file to grep or it will deadlock in pipes :<
      # List all possible patches
      # Take last workd (.*-migration)
      # Sort by number of occurences and pick the most common one
      # Spliting it into multiple lines caused errors
      sourceVersionToUseForMigration=$(cat $logFile | grep -oP 'can be rewritten automatically under -rewrite -source ([\w\.]+-migration)' | awk '{print $NF}' | sort | uniq -c | sort -nr | cut -c9- | head -n 1)
      enableMigrationMode=true
      shouldRetry=true
    fi
  fi
}

retry=0
maxRetries=2 # 1 retry for each: missing mappings (force scala version) and source migration

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
