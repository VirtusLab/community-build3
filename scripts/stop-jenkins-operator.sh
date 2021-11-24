#!/usr/bin/env bash
set -e

scriptDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
source $scriptDir/utils.sh

if [ -z "$CB_K8S_JENKINS_OPERATOR_NAMESPACE" ]; then
  echo >&2 "CB_K8S_JENKINS_OPERATOR_NAMESPACE env variable has to be set"
  exit 1
fi

helm -n "$CB_K8S_JENKINS_OPERATOR_NAMESPACE" delete operator

scbok delete -f secret license
scbok delete secret license-token
