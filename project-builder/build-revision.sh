#!/usr/bin/env bash
set -e

if [ $# -ne 8 ]; then
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
projectConfig="$8"

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

$scriptDir/checkout.sh "$repoUrl" "$rev" repo

# Wait until mvn-repo is reachable, frequently few first requests might fail
# especially in cli immediately after starting minikube
for i in {1..10}; do
  if errMsg=$(curl $mvnRepoUrl 2>&1); then
    break
  else
    echo "${errMsg}"
    echo "Waiting until mvn-repo is reachable..."
    sleep 1
  fi
done

if [ -f "repo/mill" ] || [ -f "repo/build.sc" ]; then
  echo "Mill project found: ${isMillProject}"
  $scriptDir/mill/prepare-project.sh repo "$scalaVersion" "$version"
  $scriptDir/mill/build.sh repo "$scalaVersion" "$version" "$targets" "$mvnRepoUrl" "$projectConfig"

elif [ -f "repo/build.sbt" ]; then 
  echo "sbt project found: ${isSbtProject}"
  $scriptDir/sbt/prepare-project.sh repo "$enforcedSbtVersion"
  $scriptDir/sbt/build.sh repo "$scalaVersion" "$version" "$targets" "$mvnRepoUrl" "$projectConfig"

else
  echo "Unknown project build tool, project layout:"
  ls -l repo/
  exit 1
fi
