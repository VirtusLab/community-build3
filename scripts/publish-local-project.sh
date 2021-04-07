#!/usr/bin/env bash
set -e

if [ $# -ne 3 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

projectPath="$1" #spring-maven-repository/examples/deploySbt
version=$2 #'1.0.2-communityBuild'
targets="$3" #com.example%greeter

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

source $scriptDir/env.sh

containerId=$(docker create \
  --rm \
  --network $DOCKER_NETWORK \
  --entrypoint=/build/build.sh \
  communitybuild3/executor \
  $CM_SCALA_VERSION \
  $version \
  "$targets" \
  $PROXY_HOSTNAME)

containerName=$(docker ps -a --format='{{.Names}}' -f "id=$containerId")

docker cp $projectPath "$containerName":/build/repo

docker start $containerName
docker attach $containerName
