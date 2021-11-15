#!/usr/bin/env bash
set -e

if [ $# -ne 7 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repoUrl="$1" # e.g. 'https://github.com/Stiuil06/deploySbt.git'
rev="$2" # e.g. '1.0.2'
scalaVersion="$3" # e.g. 3.0.0-RC3
version="$4" # e.g. '1.0.2-communityBuild'
targets="$5" # e.g. com.example%greeter
mvnRepoUrl="$6" # e.g. https://mvn-repo/maven2/2021-05-23_1
enforcedSbtVersion="$7" # e.g. '1.5.5' or empty '' 

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

$scriptDir/checkout.sh "$repoUrl" "$rev" repo
$scriptDir/prepare-project.sh repo "$enforcedSbtVersion"
$scriptDir/build.sh repo "$scalaVersion" "$version" "$targets" "$mvnRepoUrl" "$enforcedSbtVersion"
