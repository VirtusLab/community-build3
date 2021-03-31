#!/bin/bash

set -e

# ./run_maven_server_bg.sh
docker-compose  -f spring-maven-repository/docker-compose.yml up -d

export CM_SCALA_VERSION=3.0.0-RC1
export PROXY_HOSTNAME=nginx-proxy
export DOCKER_NETWORK=builds-network
# export PROXY_LOCATION='https://repo1.maven.org/maven2'


# build publish_project image
docker build -t communitybuild3/publish_project publish_project

# build warm_up_executor image
# docker build -t communitybuild3/warm_up_executor warm_up_executor

# # build scala-release image
# docker build -t communitybuild3/publish_scala publish_scala

# # build executor image
# docker build -t communitybuild3/executor executor


# # run publish_project
docker run --rm --network $DOCKER_NETWORK communitybuild3/publish_project \
  $CM_SCALA_VERSION \
  https://github.com/Stiuil06/deploySbt.git \
  1.0.2 \
  1.0.2-communityBuild \
  "com.example%greeter" \
  $PROXY_HOSTNAME


# # run warm_up
# docker run --rm --network builds-network communitybuild3/warm_up_executor \
#   $CM_SCALA_VERSION \
#   https://github.com/Stiuil06/warm_up.git \
#   main \
#   0.0.1-communityBuild \
#   "default%repo" \
#   $PROXY_LOCATION 

# release scala
# docker run \
#   --rm
#   --add-host repo1.maven.org:$(ipconfig getifaddr en0) \
#   --add-host repo.maven.apache.org:$(ipconfig getifaddr en0) \
#   --add-host repo1.maven.org.fake:$(ipconfig getifaddr en0) \
#   --network builds-network \
#   communitybuild3/publish_scala \
#   $CM_SCALA_VERSION https://github.com/lampepfl/dotty.git master $PROXY_LOCATION

# # run munit with new scala version
# docker run \
#   --rm
#   --add-host repo1.maven.org:$(ipconfig getifaddr en0) \
#   --add-host repo.maven.apache.org:$(ipconfig getifaddr en0) \
#   --add-host repo1.maven.org.fake:$(ipconfig getifaddr en0) \
#   --network builds-network \
#   communitybuild3/executor \
#   $CM_SCALA_VERSION https://github.com/scalameta/munit.git v0.7.22 \
#   0.7.22-communityBuild \
#   "org.scalameta%munit-scalacheck org.scalameta%munit" \
#   $PROXY_LOCATION 


docker-compose  -f spring-maven-repository/docker-compose.yml stop
