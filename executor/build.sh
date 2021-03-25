#!/usr/bin/bash

echo '##################################'
rev='v0.7.22'
repo='https://github.com/scalameta/munit.git'
repoDir=repo
scalaVersion=3.0.0-RC1-bin-SNAPSHOT
version='0.7.22-communityBuild'

set -e

git clone $repo repo -b $rev --depth 1
cp CommunityBuildPlugin.scala repo/project/CommunityBuildPlugin.scala
cd repo

# Will run: sbt -Dcommunitybuild.version=0.7.22-communityBuild ;moduleMappings ;++3.0.0-RC1-bin-SNAPSHOT! ;set every version := "0.7.22-communityBuild" ;set every credentials := Nil  ;runBuild org.scalameta%munit-scalacheck org.scalameta%munit
sbt --sbt-version 1.4.8 -Dcommunitybuild.version="$version" \
  \;moduleMappings \
  \;++"$scalaVersion"! \
  \;"set every version := \"$version\"" \
  \;"set every credentials := Nil" \
  \;"runBuild org.scalameta%munit-scalacheck org.scalameta%munit"