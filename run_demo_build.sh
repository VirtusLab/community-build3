#!/bin/bash

set -e

# ./run_maven_server_bg.sh
docker-compose  -f spring-maven-repository/docker-compose.yml up -d
# sleep 60 # mvn-proxy need a lot of time to start (probably thanks to gradle bootRun)
# TODO add health endpoint (to mvn-repo) and health check here instead hardcoded sleep

export CM_SCALA_VERSION=3.0.0-RC1
export PROXY_HOSTNAME=nginx-proxy
export DOCKER_NETWORK=builds-network
# export PROXY_LOCATION='https://repo1.maven.org/maven2'

# # build scala-release image
# docker build -t communitybuild3/publish_scala publish_scala

# build executor image
docker build -t communitybuild3/executor executor

# release scala
# docker run \
#   --rm
#   --add-host repo1.maven.org:$(ipconfig getifaddr en0) \
#   --add-host repo.maven.apache.org:$(ipconfig getifaddr en0) \
#   --add-host repo1.maven.org.fake:$(ipconfig getifaddr en0) \
#   --network builds-network \
#   communitybuild3/publish_scala \
#   $CM_SCALA_VERSION https://github.com/lampepfl/dotty.git master $PROXY_LOCATION


# run build 1
docker run --rm --network $DOCKER_NETWORK communitybuild3/executor \
  $CM_SCALA_VERSION \
  https://github.com/Stiuil06/deploySbt.git \
  1.0.2 \
  1.0.2 \
  "com.example%greeter" \
  $PROXY_HOSTNAME

# run build 2 dependend on artifact from build 1
docker run --rm --network $DOCKER_NETWORK communitybuild3/executor \
  $CM_SCALA_VERSION \
  https://github.com/Stiuil06/fetchSbt.git \
  0.0.1 \
  0.0.1 \
  "com.example%helloworld" \
  $PROXY_HOSTNAME


docker-compose  -f spring-maven-repository/docker-compose.yml stop
