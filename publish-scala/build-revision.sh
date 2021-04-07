#!/usr/bin/env bash
set -e

if [ $# -ne 4 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

#args parsing in order
repo=$1 #'https://github.com/scalameta/munit.git'
rev=$2 #'v0.7.22'
scalaVersion=$3 # 3.0.0-RC1-bin-SNAPSHOT
export PROXY_HOSTNAME=$4 #http://172.17.0.1:8443/maven2 
export serverLocation="https://repo1.maven.org/maven2"

# setup proxy location in /etc/hosts
PROXY_LOCATION=$(/build/setup-proxy-location.sh $PROXY_HOSTNAME)

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
