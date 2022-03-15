#!/usr/bin/env bash
set -e

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
cd $scriptDir/../cli

# Installation of scala-cli in the GH actions workflow was not very effective, and might have lead to missing binary on the PATH when executing this script

testNamespace=scala3-community-build-test
cliRunCmd="run scb-cli.scala --java-prop communitybuild.version=test --java-prop communitybuild.local.dir=$scriptDir/.. -- "
commonOpts="--namespace=$testNamespace --keepCluster --keepMavenRepo --noRedirectLogs"
sbtProject=typelevel/shapeless-3
millProject=com-lihaoyi/os-lib
# It's should actually build 3.1.1 release
scalaVersion=3.1.1-bin-d0e5cc45da47bedbbce6f6430052ced91c941a08-COMMUNITY-BUILD

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
