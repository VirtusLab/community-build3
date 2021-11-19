#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/env.sh

helm -n "$CM_K8S_JENKINS_OPERATOR_NAMESPACE" delete operator

scbok delete -f secret license
scbok delete secret license-token