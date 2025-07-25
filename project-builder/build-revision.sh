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
source $scriptDir/versions.sh

repoDir=$PWD/repo
$scriptDir/checkout.sh "$repoUrl" "$rev" $repoDir
buildToolFile="build-tool.txt"

if [[ ! -z $extraLibraryDeps ]]; then
  echo "Would try to append extra library dependencies (best-effort, sbt/scala-cli only): ${extraLibraryDeps}"
fi

function applySourcePatches() {
  # Base64 is used to mitigate spliting json by whitespaces
  for elem in $(echo "${projectConfig}" | jq -r '.sourcePatches // [] | .[] | @base64'); do
    function field() {
      echo ${elem} | base64 --decode | jq -r ${1}
    }
    replaceWith=$(echo "$(field '.replaceWith')" | sed "s/<SCALA_VERSION>/${scalaVersion}/")
    path=$(field '.path')
    pattern=$(field '.pattern')
    
    echo "Try apply source patch:"
    echo "Path:        $path"
    echo "Pattern:     $pattern"
    echo "Replacement: $replaceWith"
    (cd "$repoDir" && scala-cli run $scriptDir/shared/searchAndReplace.scala -- "${path}" "${pattern}" "${replaceWith}")
  done
}


sourceVersionSetting=""
function detectSourceVersion() {
  local scalaBinaryVersion=`echo $scalaVersion | cut -d . -f 1,2`
  local scalaBinaryVersionMajor=`echo $scalaVersion | cut -d . -f 1`
  local scalaBinaryVersionMinor=`echo $scalaVersion | cut -d . -f 2`
  echo "Scala binary version found: $scalaBinaryVersion"

  sourceVersion=`echo $projectConfig | jq -r '.sourceVersion // ""'`
  sourceVersionSetting=""
  
  if [[ "$sourceVersion" == "none" ]]; then
    echo "Would use project defined source version"
    return 0
  elif [[ "$sourceVersion" =~ ^([0-9]+\.[0-9]+)(-migration)?$ ]]; then
    versionPart="${BASH_REMATCH[1]}"
    if isBinVersionGreaterThan "$versionPart" "$scalaBinaryVersion" ; then
      if [[ $isMigrating == true ]]; then
        sourceVersionSetting="-source:$scalaBinaryVersion-migration"
      else
        sourceVersionSetting="-source:$scalaBinaryVersion"
      fi
      echo "Explicit source version is greater then used Scala version, it would be ignored"
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
    if [[ "${scalaBinaryVersion}" == "3.3" ]]; then
      # Allow preconfigured source version for Scala 3.3 LTS, it typically might be either 3.0-migration or future
      sourceVersionSetting="-source:$sourceVersion"
    else
      sourceVersionSetting="REQUIRE:-source:$sourceVersion"
    fi
    echo "Implicitly using source version $sourceVersion"
  fi
}

function setupProjectConfig() {
  currentTests=$(echo "$_projectConfig" | jq '.tests // "compile-only"')
  projectConfig=$(echo "$_projectConfig" | jq -c \
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
  commonAppendScalacOptions="$sourceVersionSetting"
  commonRemoveScalacOptions="-deprecation,-feature,-Xfatal-warnings,-Werror,MATCH:.*-Wconf.*any:e"

  extraScalacOptions="$commonAppendScalacOptions"
  disabledScalacOptions="$_disabledScalacOption,$commonRemoveScalacOptions"; 
  if [[ $isMigrating == true ]]; then
    extraScalacOptions="-rewrite,$extraScalacOptions"
    disabledScalacOptions="-indent,-no-indent,-new-syntax,$disabledScalacOptions"
  else 
    # Apply extraScalacOptions passed as input only when compiling with target Scala version
    extraScalacOptions="$_extraScalacOptions,$extraScalacOptions"
    disabledScalacOptions="$disabledScalacOptions"
  fi

  echo "Would try to apply common scalacOption (best-effort, sbt/mill only):"
  echo "Append: $extraScalacOptions"
  echo "Remove: $disabledScalacOptions"
}

## Git utils
BuildPatchFile=$PWD/build.patch
function createBuildPatch() {
  (cd "$repoDir" && git diff > $BuildPatchFile) 
}
function revertBuildPatch() {
  # --alow-empty is might be missing in some git versions
  maybeAllowEmpty=""
  if git apply --help | grep 'allow-empty' > /dev/null ; then
    maybeAllowEmpty="--allow-empty"
  fi
  (cd "$repoDir" && git apply --reverse $BuildPatchFile --ignore-space-change --ignore-whitespace --recount -C 1 --reject $maybeAllowEmpty || true) 
}
function commmitMigrationRewrite() {
  if [[ -z "$(cd "$repoDir" && git status --untracked-files=no --porcelain)" ]]; then
    echo "No migration rewrite changes found, would not commit"
  else 
    echo "Commit migration rewrites"
    (cd "$repoDir" && \
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
  applySourcePatches
  
  echo "----"
  echo "Starting build for $scalaVersion"
  echo "Execute tests: ${executeTests}"
  echo "started" > build-status.txt 
  # Mill
  # We check either for mill boostrap script or one of valid root build files
  if [ -f "repo/mill" ] || [ -f "repo/build.mill" ] || [ -f "repo/build.mill.scala" ] || [ -f "repo/build.sc" ]; then
    echo "Mill project found: ${isMillProject}"
    echo "mill" > $buildToolFile
    $scriptDir/mill/prepare-project.sh "$project" "$repoDir" "$scalaVersion" "$projectConfig"
    createBuildPatch
    $scriptDir/mill/build.sh "$repoDir" "$scalaVersion" "$targets" "$mvnRepoUrl" "$projectConfig" "$extraScalacOptions" "$disabledScalacOptions"
    revertBuildPatch
  ## Sbt
  ## Apparently built.sbt is a valid build file name. Accept any .sbt file
  elif ls repo/*.sbt 1> /dev/null 2>&1 ; then
    echo "sbt project found: ${isSbtProject}"
    echo "sbt" > $buildToolFile
    $scriptDir/sbt/prepare-project.sh "$project" "$repoDir" "$scalaVersion" "$projectConfig"
    createBuildPatch
    $scriptDir/sbt/build.sh "$repoDir" "$scalaVersion" "$targets" "$mvnRepoUrl" "$projectConfig" "$extraScalacOptions" "$disabledScalacOptions" "$extraLibraryDeps"
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
    scala-cli $scriptDir/scala-cli/build.scala -- "$repoDir" "$scalaVersion" "$projectConfig" "$mvnRepoUrl" "$extraLibraryDeps" "$extraScalacOptions"
  fi

  ## After build steps
  if [[ $isMigrating == true ]]; then
    commmitMigrationRewrite
  fi
}

# Find original Scala version used by project, it would be used to calculate cross scala versions
export OVERRIDEN_SCALA_VERSION=$(echo "$projectConfig" | jq -r '
  .. | objects |
  select(has("pattern") and has("replaceWith") and (.replaceWith | contains("<SCALA_VERSION>"))) |
  .pattern |
  capture("val .* = \"(?<version>[0-9]+\\.[0-9]+\\.[0-9]+)\"")? |
  .version // empty
')

if [[ -n "$OVERRIDEN_SCALA_VERSION" ]]; then
  echo "Would override fixed Scala version: $OVERRIDEN_SCALA_VERSION"
fi


for migrationScalaVersion in $(echo "$projectConfig" | jq -r '.migrationVersions // [] | .[]'); do
  scalaBinaryVersion=`echo ${_scalaVersion} | cut -d . -f 1,2`
  migrationBinaryVersion=`echo $migrationScalaVersion | cut -d . -f 1,2`
  if isBinVersionGreaterThan "$migrationBinaryVersion" "$scalaBinaryVersion" ; then
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