#!/usr/bin/env bash
set -e

if [ $# -ne 6 ]; then
  echo "Wrong number of script arguments"
  exit 1
fi

repo=$1 #'https://github.com/Stiuil06/deploySbt.git'
rev=$2 #'1.0.2'
scalaVersion=$3 # 3.0.0-RC1
version=$4 #'1.0.2-communityBuild'
targets=$5 #com.example%greeter
proxyHostname=$6 #nginx-proxy

/build/checkout.sh $repo $rev
/build/build.sh $scalaVersion $version "$targets" $proxyHostname
