#!/usr/bin/env bash
set -e
set -x

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
cd $scriptDir/../cli

# Installation of scala-cli in the GH actions workflow was not very effective, and might have lead to missing binary on the PATH when executing this script

testNamespace=scala3-community-build-test
cliRunCmd="run scb-cli.scala --jvm=11 --java-prop communitybuild.version=test --java-prop communitybuild.forced-java-version=11 --java-prop communitybuild.local.dir=$scriptDir/..  -- "
commonOpts="--namespace=$testNamespace --noRedirectLogs"
sbtProject="typelevel/shapeless-3 --revision=v3.1.0"
millProject="com-lihaoyi/os-lib --revision=0.8.1" 
scalaVersion=3.1.1

echo "Test sbt custom build in minikube"
scala-cli $cliRunCmd run $sbtProject $scalaVersion $commonOpts 
echo
echo "Test mill custom build in minikube"
scala-cli $cliRunCmd run $millProject $scalaVersion $commonOpts 

echo
echo "Test sbt custom build locally"
scala-cli $cliRunCmd run $sbtProject $scalaVersion $commonOpts --locally
echo
echo "Test mill custom build locally"
scala-cli $cliRunCmd run $millProject $scalaVersion $commonOpts --locally

echo "Tests passed"
