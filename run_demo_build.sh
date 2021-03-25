#!/usr/bin/bash

set -e

./run_maven_server_bg.sh

export CM_SCALA_VERSION=3.0.0-RC1-cm1
export PROXY_LOCATION='http://172.17.0.1:8443/maven2'


# build scala-release image
docker build -t communitybuild3/executor executor
# build executor image
docker build -t communitybuild3/publish_scala publish_scala

# release scala
docker run communitybuild3/publish_scala \
  $CM_SCALA_VERSION https://github.com/lampepfl/dotty.git master $PROXY_LOCATION

# run munit with new scala version
docker run communitybuild3/executor \
  $CM_SCALA_VERSION https://github.com/scalameta/munit.git v0.7.22 \
  0.7.22-communityBuild \
  "org.scalameta%munit-scalacheck org.scalameta%munit" \
  $PROXY_LOCATION 


kill -9 $(cat server.pid)
