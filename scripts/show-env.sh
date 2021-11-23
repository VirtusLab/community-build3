#!/usr/bin/env bash
set -e

context=$(kubectl config current-context)

echo "kubectl context:                     $context"
echo "CB_K8S_NAMESPACE:                    $CB_K8S_NAMESPACE"
echo "CB_K8S_JENKINS_OPERATOR_NAMESPACE:   $CB_K8S_JENKINS_OPERATOR_NAMESPACE"
