#!/usr/bin/env bash
set -e

# Simple script to run projects locally based on the current projects config

if [ $# -lt 1 ]; then
  echo "Wrong number of script arguments, got $# expected at least 1 <projectName> <scalaVersion?>"
  exit 1
fi
scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

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


$scriptDir/../project-builder/build-revision.sh \
  "$(config .project)" \
  "$(config .repoUrl)" \
  "$(config .revision)" \
  "${scalaVersion}" \
  "$(config .version)" \
  "$(config .targets)" \
  "" \
  "$(config .config // ${DefaultConfig})" \
  "$extraScalacOptions" \
  "$disabledScalacOptions" \
  "$extraLibraryDependencies" \
  2>&1 | tee build-logs.txt

cat build-status.txt