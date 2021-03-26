#!/usr/bin/bash


set -e

#args parsing in order
scalaVersion=$1 # 3.0.0-RC1-bin-SNAPSHOT
repo=$2 #'https://github.com/scalameta/munit.git'
rev=$3 #'v0.7.22'
export serverLocation=$4 #http://172.17.0.1:8443/maven2 

echo '##################################'
echo Release Scala in version: $scalaVersion
echo Clonning $repo using revision $rev
echo Maven proxy at: $serverLocation
echo '##################################'

git clone $repo repo -b $rev --depth 1
cd repo

sed -i 's/val baseVersion = "3.0.0-RC2"/val baseVersion = "'$scalaVersion'"/' project/Build.scala 
export RELEASEBUILD=yes

sbt --sbt-version $SBT_VERSION  \
  \; 'set every sonatypePublishToBundle := Some(("proxy" at sys.env("serverLocation")).withAllowInsecureProtocol(true))'  \
  \;"scala3-bootstrapped/publish"