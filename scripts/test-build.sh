#!/usr/bin/env bash
set -e 

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"

testNamespace=scala3-community-build-test
publishScalaTimeout=12m
executorTimeout=5m

kubectl delete namespace $testNamespace --ignore-not-found=true
kubectl create namespace $testNamespace

kubectl -n $testNamespace apply -f $scriptDir/../k8s/maven-data.yaml
kubectl -n $testNamespace apply -f $scriptDir/../k8s/maven.yaml

kubectl -n $testNamespace apply -f $scriptDir/../k8s/test-publish-scala.yaml
kubectl -n $testNamespace wait --timeout=$publishScalaTimeout --for=condition=complete job/publish-scala-test

publishScalaResult=$(kubectl -n $testNamespace logs job/publish-scala-test --tail=1)

if [ "$publishScalaResult" != "Scala published successfully" ]; then
  echo "Failed to publish scala"
  exit -1
fi

kubectl -n $testNamespace apply -f $scriptDir/../k8s/test-executor.yaml
kubectl -n $testNamespace wait --timeout=$executorTimeout --for=condition=complete job/executor-test

executorResult=$(kubectl -n $testNamespace logs job/executor-test --tail=1)

if [ "$executorResult" != "Community project published successfully" ]; then
  echo "Failed to publish the community project"
  exit -1
fi

kubectl delete namespace $testNamespace

echo "Test passed"
