#!/usr/bin/env bash
set -e

# Simple script to run projects locally based on the current projects config

if [ $# -lt 1 ]; then
  echo "Wrong number of script arguments, got $# expected at least 1 <projectName> <scalaVersion?>"
  exit 1
fi
scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
. $scriptDir/../project-builder/versions.sh

projectName=$1
scalaVersion=$2
if [[ -z $scalaVersion ]]; then
  scalaVersion=`${scriptDir}/lastVersionNightly.sc`
fi
extraScalacOptions=""
disabledScalacOptions=""
extraLibraryDependencies=""

echo "projectName: $projectName"
echo "scalaVersion: $scalaVersion"

ConfigFile="${scriptDir}/../.github/workflows/buildConfig.json"
function config () { 
  path=".\"$projectName\"$@" 
  jq -c -r "$path" $ConfigFile 
}
DefaultConfig="{}"


[ "$SKIP_BUILD_SETUP" != "1" ] && scala-cli run ${scriptDir}/../coordinator -- 3 1 1 1 "$projectName" ./coordinator/configs/

publishScalaVersion="$(config .publishedScalaVersion)"
if [[ "$publishScalaVersion" != "null" ]] && isBinVersionGreaterThan "$publishScalaVersion" "$scalaVersion" ; then
  echo "Warning: project published with Scala $publishScalaVersion - cannot guarantee it would work with older Scala version $scalaVersion"
fi 

unset GPG_TTY

if [[ -f $scriptDir/../.secrets/akka-repo-token ]]; then
    export OPENCB_AKKA_REPO_TOKEN=$(cat $scriptDir/../.secrets/akka-repo-token)
    # Variable that can be found in the projects
    export AKKA_IO_REPOSITORY_KEY=${OPENCB_AKKA_REPO_TOKEN}
fi
export OPENCB_GIT_DEPTH=1 
export OPENCB_EXECUTE_TESTS=true 
$scriptDir/../project-builder/build-revision.sh \
  "$(config .project)" \
  "$(config .repoUrl)" \
  "$(config .revision)" \
  "${scalaVersion}" \
  "$(config .targets)" \
  "https://scala3.westeurope.cloudapp.azure.com/maven2/$scalaVersion/" \
  "$(config .config // ${DefaultConfig})" \
  "$extraScalacOptions" \
  "$disabledScalacOptions" \
  "$extraLibraryDependencies" 2>&1 | tee build-logs.txt

echo "------"
echo "$projectName status=$(cat build-status.txt)"
echo "-------"
if [[ $(cat build-status.txt) != "success" ]]; then exit 1; fi
