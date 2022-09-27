#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

testNamespace=scala3-community-build-test
projectBuilderTimeout=5m

kubectl delete namespace $testNamespace --ignore-not-found=true
kubectl create namespace $testNamespace


function mavenRepoFailed() {
  echo "Failed to start maven repo:"
  echo "Logs content:"
  echo
  kubectl -n $testNamespace logs deploy/mvn-repo
  exit -1
}

CB_VERSION="test" \
  CB_K8S_NAMESPACE="${testNamespace}" \
  $scriptDir/start-mvn-repo.sh
kubectl -n $testNamespace get po
kubectl -n $testNamespace wait --timeout=3m --for=condition=Available deploy/mvn-repo || mavenRepoFailed
kubectl -n $testNamespace get po

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
