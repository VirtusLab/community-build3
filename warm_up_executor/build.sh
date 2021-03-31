#!/bin/bash



set -e

#args parsing in order
scalaVersion=$1 # 3.0.0-RC1-bin-SNAPSHOT
repo=$2 #'https://github.com/scalameta/munit.git'
rev=$3 #'v0.7.22'
version=$4 #'0.7.22-communityBuild'
targets=$5
export serverLocation=$6 #http://172.17.0.1:8443/maven2 

echo '##################################'
echo Scala version: $scalaVersion
echo Clonning $repo using revision $rev
echo Disting as $version for targets: $targets
echo Maven proxy at: $serverLocation
echo '##################################'

git clone $repo repo -b $rev --depth 1
cp CommunityBuildPlugin.scala repo/project/CommunityBuildPlugin.scala
cd repo

sbt --sbt-version $SBT_VERSION  \
  \;moduleMappings \
  \;++"$scalaVersion"! \
  \;"set every version := \"$version\"" \
  \;"set every credentials := Nil" \
  \;"runBuild $targets"