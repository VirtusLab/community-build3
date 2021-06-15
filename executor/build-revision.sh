#!/usr/bin/env bash
set -e

if [ $# -ne 6 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repoUrl="$1" #'https://github.com/Stiuil06/deploySbt.git'
rev="$2" #'1.0.2'
scalaVersion="$3" # 3.0.0-RC3
version="$4" #'1.0.2-communityBuild'
targets="$5" #com.example%greeter
mvnRepoUrl="$6" #https://mvm-repo/maven2/2021-05-23_1

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

$scriptDir/checkout.sh "$repoUrl" "$rev" repo
$scriptDir/prepare-project.sh repo
$scriptDir/build.sh repo "$scalaVersion" "$version" "$targets" "$mvnRepoUrl"
