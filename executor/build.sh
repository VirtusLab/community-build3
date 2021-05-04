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
#export serverLocation="https://repo1.maven.org/maven2"
# export serverLocation="https://mvn-repo:8081/maven2"
export serverLocation="http://mvn-repo:8081/maven2"

# setup proxy location in /etc/hosts
#PROXY_LOCATION=$(/build/setup-proxy-location.sh $PROXY_HOSTNAME)

echo '##################################'
echo Scala version: $scalaVersion
echo Disting version $version for targets: $targets
#echo Maven proxy at: $PROXY_LOCATION
echo '##################################'

cp /build/CommunityBuildPlugin.scala repo/project/CommunityBuildPlugin.scala
cd repo

sbt --sbt-version $SBT_VERSION -Dcommunitybuild.version="$version" \
  \;moduleMappings \
  \;++"$scalaVersion"! \
  \;"set every version := \"$version\"" \
  \;"set every credentials := Nil" \
  \;"runBuild $targets"
