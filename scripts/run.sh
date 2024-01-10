#!/usr/bin/env bash
set -e

# Simple script to run projects locally based on the current projects config

if [ $# -ne 1 ]; then
  echo "Wrong number of script arguments, got $# expected 1"
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
  '1.6.2' \
  "$(config .config // ${DefaultConfig})" \
  "$extraScalacOptions" \
  "$disabledScalacOptions" 2>&1 | tee build-logs.txt

cat build-status.txt