#!/usr/bin/env bash
set -e
set -o pipefail

if [ $# -ne 8 ]; then
  echo "Wrong number of script arguments, expected $0 <repo_dir> <scala-version> <targets> <maven_repo> <project_config?> <extraScalacOpts?> <removeScalacOpts?> <extraDeps?>, got $#: $@"
  exit 1
fi

repoDir="$1"                # e.g. /tmp/shapeless
scalaVersion="$2"           # e.g. 3.0.1-RC1-bin-COMMUNITY-SNAPSHOT
targets=($3)                # e.g. "com.example%foo com.example%bar"
export CB_MVN_REPO_URL="$4" # e.g. https://mvn-repo/maven2/2021-05-23_1
projectConfig="$5"
extraScalacOptions="$6"
disabledScalacOption="$7"
extraLibraryDeps="$8"

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

if [[ -z "$projectConfig" ]]; then
  projectConfig="{}"
fi

echo '##################################'
echo Scala version: $scalaVersion
echo Targets: ${targets[@]}
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

sbtSettings=(
  --batch
  --verbose
  "-Dcommunitybuild.scala=$scalaVersion"
  "-Dcommunitybuild.project.dependencies.add=$extraLibraryDeps"
  ${memorySettings[@]}
  $(echo $projectConfig | jq -r '.sbt.options? // [] | join(" ")' | sed "s/<SCALA_VERSION>/${scalaVersion}/g")
)
customCommands=$(echo "$projectConfig" | jq -r '.sbt?.commands // [] | join ("; ")')
targetsString="${targets[@]}"
logFile=build.log

# Compiler plugins, cannot be cross-published before starting the build
# Allways exclude these from library dependencies
excludedCompilerPlugins=(
  "com.github.ghik:zerowaste_{scalaVersion}"
  "com.olegpy:better-monadic-for_3"
  "org.polyvariant:better-tostring_{scalaVersion}"
  "org.wartremover:wartremover_{scalaVersion}"
)
excludedCompilerPluginOptPrefixes=(
  "-P:wartremover"
)

shouldRetry=false
forceScalaVersion=false
appendScalacOptions="${extraScalacOptions}"
removeScalacOptions="${disabledScalacOption}"

function runSbt() {
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
    "excludeLibraryDependency ${excludedCompilerPlugins[*]}" \
    "removeScalacOptionsStartingWith ${excludedCompilerPluginOptPrefixes[*]}" \
    "$customCommands" \
    "moduleMappings" \
    "runBuild ${scalaVersion} ${tq}${projectConfig}${tq} $targetsString" 2>&1 | tee $logFile
}

function checkLogsForRetry() {
  # Retry only when given modes were not tried yet
  shouldRetry=false
  # Failed to download artifacts
  if grep -q 'sbt.librarymanagement.ResolveException' "$logFile"; then
    TIMEOUT=$(( RANDOM % 241 + 60 ))
    echo "Failed to download artifacts, retry after $TIMEOUT seconds"
    sleep "$TIMEOUT"
    shouldRetry=true
  fi

  # Failed to switch version
  if [ "$forceScalaVersion" = false ]; then
    if grep -q 'Switch failed:' "$logFile"; then
      forceScalaVersion=true
      shouldRetry=true
    elif grep -q 'Module mapping missing:' "$logFile" && grep -q -e 'moduleIds: .*_2\.1[1-3]' "$logFile"; then
      # Incorrect mappings using Scala 2.13
      forceScalaVersion=true
      shouldRetry=true
    elif grep -q -E "Your tlBaseVersion (.*) is behind the latest tag (.*)" "$logFile"; then
      # TypelevelVersioningPlugin workaround, might get broken after migration (commiting changes)
      newTag=$(grep 'Your tlBaseVersion [0-9]\+\.[0-9]\+ is behind the latest tag' "$logFile" | sed -E 's/.*latest tag ([0-9]+\.[0-9]+).*/\1/' | head -n 1 )
      for path in $(grep -R . -e 'tlBaseVersion := ' | awk 'BEGIN { FS = "[ :]+" } { print $1 }'); do
        scala-cli run $scriptDir/../shared/searchAndReplace.scala -- "${path}" 'tlBaseVersion := [^,\n]+' "tlBaseVersion := \"$newTag\""
      done
      shouldRetry=true
    fi
  fi
}

retry=0
maxRetries=2 # 1 retry for each: missing mappings (force scala version)

function retryBuild() {
  while [[ $retry -lt $maxRetries ]]; do
    checkLogsForRetry
    if [[ "$shouldRetry" == "true" ]]; then
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
