#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

testNamespace=scala3-community-build-test
compilerBuilderTimeout=15m
projectBuilderTimeout=5m

export MVN_REPO_KEYSTORE_PASSWORD=$(openssl rand -base64 32)

kubectl delete namespace $testNamespace --ignore-not-found=true
kubectl create namespace $testNamespace

$scriptDir/generate-secrets.sh

kubectl -n $testNamespace create secret generic mvn-repo-keystore --from-file=$scriptDir/../secrets/mvn-repo.p12
kubectl -n $testNamespace create secret generic mvn-repo-passwords --from-literal=keystore-password="$MVN_REPO_KEYSTORE_PASSWORD"
kubectl -n $testNamespace create cm mvn-repo-cert --from-file=$scriptDir/../secrets/mvn-repo.crt
kubectl -n $testNamespace apply -f $scriptDir/../k8s/mvn-repo-data.yaml
kubectl -n $testNamespace apply -f $scriptDir/../k8s/mvn-repo.yaml

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
  echo "Failed to publish the community project"
  echo "Logs content:"
  echo
  kubectl -n $testNamespace logs job/project-builder-test
  exit -1
}

kubectl -n $testNamespace apply -f $scriptDir/../k8s/project-builder-test.yaml
echo "Building a community project"
kubectl -n $testNamespace wait --timeout=$projectBuilderTimeout --for=condition=complete job/project-builder-test || projectBuilderFailed

projectBuilderResult=$(kubectl -n $testNamespace logs job/project-builder-test --tail=1)
test "$projectBuilderResult" == "Community project published successfully" || projectBuilderFailed

kubectl delete namespace $testNamespace

echo "Test passed"
