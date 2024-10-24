#!/usr/bin/env bash
set -e

if [ $# -ne 10 ]; then
  echo "Wrong number of script arguments, got $# expected 10"
  exit 1
fi
project="$1"
repoUrl="$2"            # e.g. 'https://github.com/Stiuil06/deploySbt.git'
rev="$3"                # e.g. '1.0.2'
_scalaVersion="$4"       # e.g. 3.0.0-RC3
targets="$5"            # e.g. com.example%greeter
mvnRepoUrl="$6"         # e.g. https://mvn-repo/maven2/2021-05-23_1
_projectConfig="$7"
_extraScalacOptions="${8}" # e.g '' or "-Wunused:all -Ylightweight-lazy-vals"
_disabledScalacOptions="${9}"
extraLibraryDeps="${10}" # format org:artifact:version, eg. org.scala-lang:scala2-library-tasty_3:3.4.0-RC1

_executeTests=${OPENCB_EXECUTE_TESTS:-false}

# Mutable 
scalaVersion=${_scalaVersion}
projectConfig=${_projectConfig}
extraScalacOptions=${_extraScalacOptions}
disabledScalacOptions=${_disabledScalacOptions}
executeTests=${_executeTests}

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

export OPENCB_SCRIPT_DIR=$scriptDir

$scriptDir/checkout.sh "$repoUrl" "$rev" repo
buildToolFile="build-tool.txt"

if [[ ! -z $extraLibraryDeps ]]; then
  echo "Would try to append extra library dependencies (best-effort, sbt/scala-cli only): ${extraLibraryDeps}"
fi

function isBinVersionGreaterThen() {
    version1=$1
    version2=$2

    if [[ $version1 == $version2 ]]; then
        return 1
    fi

    IFS='.' read -ra v1 <<< "$version1"
    IFS='.' read -ra v2 <<< "$version2"

    # Compare major version
    if [[ ${v1[0]} -lt ${v2[0]} ]]; then
        return 1
    elif [[ ${v1[0]} -gt ${v2[0]} ]]; then
        return 0
    fi

    # Major versions are equal, compare minor version
    if [[ ${v1[1]} -lt ${v2[1]} ]]; then
        return 1
    elif [[ ${v1[1]} -gt ${v2[1]} ]]; then
        return 0
    fi
}

sourceVersionSetting=""
function detectSourceVersion() {
  local scalaBinaryVersion=`echo $scalaVersion | cut -d . -f 1,2`
  local scalaBinaryVersionMajor=`echo $scalaVersion | cut -d . -f 1`
  local scalaBinaryVersionMinor=`echo $scalaVersion | cut -d . -f 2`
  echo "Scala binary version found: $scalaBinaryVersion"

  sourceVersion=`echo $projectConfig | jq -r '.sourceVersion // ""'`
  sourceVersionSetting=""

  if [[ "$sourceVersion" =~ ^([0-9]+\.[0-9]+)(-migration)?$ ]]; then
    versionPart="${BASH_REMATCH[1]}"
    if isBinVersionGreaterThen "$versionPart" "$scalaBinaryVersion" ; then
      if [[ $isMigrating == true ]]; then
        sourceVersionSetting="-source:$scalaBinaryVersion-migration"
      else
        sourceVersionSetting="-source:$scalaBinaryVersion"
      fi
      echo "Explicit source version is great then used Scala version, it would be ignored"
    else 
      echo "Using configured source version: $sourceVersion"
      sourceVersionSetting="REQUIRE:-source:$sourceVersion"
    fi
  elif [[ -z "$sourceVersion" ]]; then 
    echo "No configured source version" > /dev/null
  else
    echo "Configured version `$sourceVersion` is invalid, it would be ignored"
  fi
  if [[ -z "$sourceVersionSetting" ]]; then
    sourceVersion="$scalaBinaryVersion-migration"
    sourceVersionSetting="-source:$sourceVersion"
    echo "Implicitly using source version $sourceVersion"
  fi
}

function setupProjectConfig() {
  currentTests=$(echo "$_projectConfig" | jq '.tests // "compile-only"')
  projectConfig=$(echo "$_projectConfig" | jq \
    --argjson executeTests $executeTests \
    --argjson currentTests $currentTests \
    '.tests = if $executeTests then 
        .tests 
      else 
        if $currentTests=="full" then "compile-only" else $currentTests end
      end')
}


isMigrating=false
function setupScalacOptions(){
  detectSourceVersion
  commonAppendScalacOptions="$sourceVersionSetting,-Wconf:msg=can be rewritten automatically under:s"
  commonRemoveScalacOptions="-deprecation,-feature,-Xfatal-warnings,-Werror,MATCH:.*-Wconf.*any:e,-migration,"

  extraScalacOptions="$commonAppendScalacOptions"
  disabledScalacOptions="$_disabledScalacOption,$commonRemoveScalacOptions"; 
  if [[ $isMigrating == true ]]; then
    extraScalacOptions="-rewrite,$extraScalacOptions"
    disabledScalacOptions="-indent,-no-indent,-new-syntax,$disabledScalacOptions"
  else 
    # Apply extraScalacOptions passed as input only when compiling with target Scala version
    extraScalacOptions="$_extraScalacOptions,$extraScalacOptions"
  fi

  echo "Would try to apply common scalacOption (best-effort, sbt/mill only):"
  echo "Append: $extraScalacOptions"
  echo "Remove: $disabledScalacOptions"
}

## Git utils
BuildPatchFile=$PWD/build.patch
function createBuildPatch() {
  (cd repo && git diff > $BuildPatchFile) 
}
function revertBuildPatch() {
  (cd repo && git apply --reverse $BuildPatchFile --ignore-space-change --ignore-whitespace --recount -C 1 --reject --allow-empty || true) 
}
function commmitMigrationRewrite() {
  if [[ -z "$(cd repo && git status --untracked-files=no --porcelain)" ]]; then
    echo "No migration rewrite changes found, would not commit"
  else 
    echo "Commit migration rewrites"
    (cd repo && \
      git checkout -b "opencb/migrate-$scalaVersion"
      git add -u . && \
      git commit -m "Apply Scala compiler rewrites for $scalaVersion"\
    ) 
  fi
}

function buildForScalaVersion(){
  scalaVersion=$1
  echo "----"
  echo "Preparing build for $scalaVersion"
  detectSourceVersion
  setupScalacOptions
  setupProjectConfig

  echo "----"
  echo "Starting build for $scalaVersion"
  echo "Execute tests: ${executeTests}"
  echo "started" > build-status.txt 
  # Mill
  # We check either for mill boostrap script or one of valid root build files
  if [ -f "repo/mill" ] || [ -f "repo/build.mill" ] || [ -f "repo/build.mill.scala"] || [ -f "repo/build.sc"]; then
    echo "Mill project found: ${isMillProject}"
    echo "mill" > $buildToolFile
    $scriptDir/mill/prepare-project.sh "$project" repo "$scalaVersion" "$projectConfig"
    createBuildPatch
    $scriptDir/mill/build.sh repo "$scalaVersion" "$targets" "$mvnRepoUrl" "$projectConfig" "$extraScalacOptions" "$disabledScalacOptions"
    revertBuildPatch
  ## Sbt
  ## Apparently built.sbt is a valid build file name. Accept any .sbt file
  elif ls repo/*.sbt 1> /dev/null 2>&1 ; then
    echo "sbt project found: ${isSbtProject}"
    echo "sbt" > $buildToolFile
    $scriptDir/sbt/prepare-project.sh "$project" repo "$scalaVersion" "$projectConfig"
    createBuildPatch
    $scriptDir/sbt/build.sh repo "$scalaVersion" "$targets" "$mvnRepoUrl" "$projectConfig" "$extraScalacOptions" "$disabledScalacOptions" "$extraLibraryDeps"
    revertBuildPatch
  ## Scala-cli
  else
    echo "Not found sbt or mill build files, assuming scala-cli project"
    ls -l repo/
    echo "scala-cli" > $buildToolFile
    export COURSIER_REPOSITORIES="central|sonatype:releases|$mvnRepoUrl"
    scala-cli config power true
    scala-cli bloop exit
    scala-cli clean $scriptDir/scala-cli/
    scala-cli clean repo
    scala-cli $scriptDir/scala-cli/build.scala -- repo "$scalaVersion" "$projectConfig" "$mvnRepoUrl" "$extraLibraryDeps" "$extraScalacOptions"
  fi

  ## After build steps
  if [[ $isMigrating == true ]]; then
    commmitMigrationRewrite
  fi
}

for migrationScalaVersion in $(echo "$projectConfig" | jq -r '.migrationVersions // [] | .[]'); do
  scalaBinaryVersion=`echo ${_scalaVersion} | cut -d . -f 1,2`
  migrationBinaryVersion=`echo $migrationScalaVersion | cut -d . -f 1,2`
  if isBinVersionGreaterThen "$migrationBinaryVersion" "$scalaBinaryVersion" ; then
    echo "Skip migration using $migrationScalaVersion, binary version higher then target Scala version $scalaBinaryVersion"
  else 
    isMigrating=true
    executeTests=false
    echo "Migrating project using Scala $migrationScalaVersion"
    buildForScalaVersion $migrationScalaVersion
    executeTests=${_executeTests}
    isMigrating=false
  fi
done

buildForScalaVersion $_scalaVersion