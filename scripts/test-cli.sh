#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
cd $scriptDir/../cli

testNamespace=scala3-community-build-test
cliRunCmd="run scb-cli.scala --java-prop communitybuild.version=test --java-prop communitybuild.local.dir=$scriptDir/.. -- "
commonOpts="--namespace=$testNamespace --keepCluster --keepMavenRepo"
sbtProject=typelevel/shapeless-3
millProject=com-lihaoyi/os-lib
scalaVersion=3.1.1

echo "Test sbt custom build locally"
scala-cli $cliRunCmd run $sbtProject $scalaVersion $commonOpts --locally 

echo
echo "Test sbt custom build in minikube"
scala-cli $cliRunCmd run $sbtProject $scalaVersion $commonOpts

echo
echo "Test mill custom build locally"
scala-cli $cliRunCmd run $millProject $scalaVersion $commonOpts --locally

echo
echo "Test mill custom build in minikube"
scala-cli $cliRunCmd run $millProject $scalaVersion $commonOpts

echo "Tests passed"