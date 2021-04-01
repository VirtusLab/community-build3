#!/bin/bash
set -e

#args parsing in order
scalaVersion=$1 # 3.0.0-RC1
repo=$2 #'https://github.com/Stiuil06/deploySbt.git'
rev=$3 #'1.0.2'
version=$4 #'1.0.2-communityBuild'
targets=$5 #com.example%greeter
export PROXY_HOSTNAME=$6 #nginx-proxy
export serverLocation="https://repo1.maven.org/maven2"

# setup proxy location in /etc/hosts
PROXY_LOCATION=$(/build/setup_proxy_location.sh $PROXY_HOSTNAME)

echo '##################################'
echo Scala version: $scalaVersion
echo Clonning $repo using revision $rev
echo Disting as $version for targets: $targets
echo Maven proxy at: $PROXY_LOCATION
echo '##################################'

git clone $repo repo -b $rev --depth 1
cp CommunityBuildPlugin.scala repo/project/CommunityBuildPlugin.scala
cd repo

sbt --sbt-version $SBT_VERSION -Dcommunitybuild.version="$version" \
  \;moduleMappings \
  \;++"$scalaVersion"! \
  \;"set every version := \"$version\"" \
  \;"set every credentials := Nil" \
  \;"runBuild $targets"