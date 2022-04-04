#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

testNamespace=scala3-community-build-test
compilerBuilderTimeout=60m
projectBuilderTimeout=5m

kubectl delete namespace $testNamespace --ignore-not-found=true
kubectl create namespace $testNamespace

CB_VERSION="test" \
CB_K8S_NAMESPACE="${testNamespace}" \
$scriptDir/start-mvn-repo.sh

function compilerBuilderFailed() {
  echo "Failed to publish scala"
  echo "Logs content:"
  echo
  kubectl -n $testNamespace logs job/compiler-builder-test
  exit -1
}

kubectl -n $testNamespace apply -f $scriptDir/../k8s/compiler-builder-test.yaml
echo "Building scala compiler"
kubectl -n $testNamespace wait --timeout=$compilerBuilderTimeout --for=condition=complete job/compiler-builder-test || compilerBuilderFailed

compilerBuilderResult=$(kubectl -n $testNamespace logs job/compiler-builder-test --tail=1)
test "$compilerBuilderResult" == "Compiler published successfully" || compilerBuilderFailed

function projectBuilderFailed() {
  jobName="$1"
  echo "Failed to publish the community project"
  echo "Logs content:"
  echo
  kubectl -n $testNamespace logs job/${jobName}
  exit -1
}

function testBuildTool() {
  tool="$1"
  jobName="project-builder-${tool}-test"
  echo "Building a ${tool} community project"
  kubectl -n $testNamespace apply -f $scriptDir/../k8s/${jobName}.yaml
  kubectl -n $testNamespace wait --timeout=$projectBuilderTimeout --for=condition=complete job/${jobName} || projectBuilderFailed ${jobName}

  projectBuilderResult=$(kubectl -n $testNamespace logs job/${jobName} --tail=1)
  test "$projectBuilderResult" == "Community project published successfully" || projectBuilderFailed ${jobName}
}

testBuildTool sbt
testBuildTool mill

kubectl delete namespace $testNamespace

echo "Test passed"
