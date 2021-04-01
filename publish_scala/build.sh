#!/usr/bin/env bash


set -e

#args parsing in order
scalaVersion=$1 # 3.0.0-RC1-bin-SNAPSHOT
repo=$2 #'https://github.com/scalameta/munit.git'
rev=$3 #'v0.7.22'
export PROXY_HOSTNAME=$4 #http://172.17.0.1:8443/maven2 
export serverLocation="https://repo1.maven.org/maven2"

# setup proxy location in /etc/hosts
PROXY_LOCATION=$(/build/setup_proxy_location.sh $PROXY_HOSTNAME)

echo '##################################'
echo Release Scala in version: $scalaVersion
echo Clonning $repo using revision $rev
echo Maven proxy at: $PROXY_LOCATION
echo '##################################'

git clone $repo repo -b $rev --depth 1
cd repo

sed -i -r 's/val baseVersion = ".*"/val baseVersion = "'$scalaVersion'"/' project/Build.scala 
export RELEASEBUILD=yes

sbt --sbt-version $SBT_VERSION  \
  \;'set every sonatypePublishToBundle := Some(("proxy" at sys.env("serverLocation")).withAllowInsecureProtocol(true))'  \
  \;"scala3-bootstrapped/publish"