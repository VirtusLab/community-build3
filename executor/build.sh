#!/usr/bin/env bash
set -e

if [ $# -ne 4 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

#args parsing in order
scalaVersion=$1 # 3.0.0-RC3
version=$2 #'1.0.2-communityBuild'
targets=$3 #com.example%greeter
export PROXY_HOSTNAME=$4 #nginx-proxy
export serverLocation="http://mvn-repo:8081/maven2"

echo '##################################'
echo Scala version: $scalaVersion
echo Disting version $version for targets: $targets
echo '##################################'

cp /build/CommunityBuildPlugin.scala repo/project/CommunityBuildPlugin.scala
cd repo

sbt -Dcommunitybuild.version="$version" \
  \;moduleMappings \
  \;++"$scalaVersion"! \
  \;"set every version := \"$version\"" \
  \;"set every credentials := Nil" \
  \;"runBuild $targets"
