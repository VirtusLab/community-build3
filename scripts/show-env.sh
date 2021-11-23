#!/usr/bin/env bash
set -e

context=$(kubectl config current-context)

echo "kubectl context:                     $context"
echo "CM_K8S_NAMESPACE:                    $CM_K8S_NAMESPACE"
echo "CM_K8S_JENKINS_OPERATOR_NAMESPACE:   $CM_K8S_JENKINS_OPERATOR_NAMESPACE"
